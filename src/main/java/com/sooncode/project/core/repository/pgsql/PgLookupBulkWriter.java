package com.sooncode.project.core.repository.pgsql;

import com.sooncode.project.core.config.InfraConfig;
import com.sooncode.project.core.lookup.ILookupBulkWriter;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.Entity;
import com.sooncode.project.core.model.SnapshotWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * PGSQL Lookup 快照批量写入器（BEST_EFFORT，最终一致，对标 {@code MongoLookupBulkWriter}）。
 *
 * <p>只做快照表的 upsert（{@code INSERT ... ON CONFLICT DO UPDATE}），不碰元数据与事件流，
 * 失败仅记日志，不污染业务事务。
 */
public class PgLookupBulkWriter implements ILookupBulkWriter {

    private static final Logger log = LoggerFactory.getLogger(PgLookupBulkWriter.class);

    private final PgDao dao;
    private volatile int bulkBatchSize;

    public PgLookupBulkWriter(PgDao dao, int bulkBatchSize) {
        if (dao == null) {
            throw new DomainException("PgLookupBulkWriter 构造失败：dao 不能为空");
        }
        this.dao = dao;
        this.bulkBatchSize = bulkBatchSize > 0 ? bulkBatchSize : 500;
    }

    /**
     * 刷新批量写入器的动态配置（对标 {@code MongoLookupBulkWriter.refresh}）。
     * 由 {@code LookupHandler.refreshConfig()} 调用。
     */
    @Override
    public void refresh() {
        try {
            int n = InfraConfig.getLookupBulkBatchSize();
            if (n > 0) {
                bulkBatchSize = n;
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public int bulkSaveSnapshots(Class<?> modelType, List<Entity> entities) {
        if (modelType == null) {
            throw new IllegalArgumentException("modelType 不能为空");
        }
        if (entities == null || entities.isEmpty()) {
            return 0;
        }
        String table = PgDocumentMapper.snapshotTable(modelType);
        dao.ensureSnapshotTable(table);
        String sql = "INSERT INTO " + dao.q(table)
                + " (stream_id, snapshot_type, snapshot, create_date) VALUES (?,?,?::jsonb,?)"
                + " ON CONFLICT (stream_id) DO UPDATE SET snapshot_type=EXCLUDED.snapshot_type,"
                + " snapshot=EXCLUDED.snapshot, create_date=EXCLUDED.create_date";
        int effectiveBatch = effectiveBatchSize();
        int success = 0;
        try (Connection c = dao.connection()) {
            List<Entity> batch = new ArrayList<>(effectiveBatch);
            for (Entity entity : entities) {
                // 对标 MongoLookupBulkWriter：空实体/空 id 跳过（不写出 "null" 脏 stream_id）
                if (entity == null || entity.getId() == null) {
                    log.warn("[Lookup][Bulk] 跳过空实体或空 id, modelType={}", modelType.getSimpleName());
                    continue;
                }
                batch.add(entity);
                if (batch.size() >= effectiveBatch) {
                    success += executeBatch(c, sql, modelType, batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                success += executeBatch(c, sql, modelType, batch);
            }
        } catch (SQLException e) {
            log.warn("PGSQL Lookup 批量快照写入失败，已成功 {} 条: {}", success, e.getMessage());
        }
        return success;
    }

    private int effectiveBatchSize() {
        try {
            int n = InfraConfig.getLookupBulkBatchSize();
            if (n > 0) {
                return n;
            }
        } catch (Exception ignored) {
        }
        return bulkBatchSize > 0 ? bulkBatchSize : 500;
    }

    private int executeBatch(Connection c, String sql, Class<?> modelType, List<Entity> batch) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Entity entity : batch) {
                // streamId 必须按目标聚合类型组装（类全名-id），与 Mongo 的 streamNameFor(modelType, id)
                // 及 BatchOperation.streamName() 一致；用实体运行时类会在代理/子类场景下写错 id。
                SnapshotWrapper wrapper = new SnapshotWrapper(
                        PgDocumentMapper.streamName(modelType, entity.getId()), entity);
                ps.setString(1, wrapper.getStreamId());
                ps.setString(2, wrapper.getSnapshotType().getName());
                ps.setString(3, PgDocumentMapper.toJson(wrapper.getSnapshot()));
                Timestamp ts = PgDao.ts(wrapper.getCreateDate() == null ? new Date() : wrapper.getCreateDate());
                ps.setTimestamp(4, ts);
                ps.addBatch();
            }
            int count = 0;
            for (int r : ps.executeBatch()) {
                if (r != PreparedStatement.EXECUTE_FAILED) {
                    count++;
                }
            }
            return count;
        }
    }
}
