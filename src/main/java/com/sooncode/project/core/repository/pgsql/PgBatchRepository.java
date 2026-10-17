package com.sooncode.project.core.repository.pgsql;

import com.sooncode.project.core.batcher.BatchOperation;
import com.sooncode.project.core.batcher.BatchPlan;
import com.sooncode.project.core.batcher.BatchPlanner;
import com.sooncode.project.core.batcher.IBatchRepository;
import com.sooncode.project.core.model.CheckForConcurrencyException;
import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.EventStream;
import com.sooncode.project.core.model.EventWrapper;
import com.sooncode.project.core.model.SnapshotWrapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * PGSQL 批量持久化仓储（对标 {@code mongo.BatchRepository}）。
 *
 * <p>语义逐项对齐：
 * <ul>
 *   <li>{@code atomic=true}：单事务，任一失败整体回滚；</li>
 *   <li>{@code atomic=false}：自动提交，已写入的不回滚；</li>
 *   <li>ADD 重复（元数据或快照已存在）→ {@code DomainException("实体已经存在:")}；</li>
 *   <li>MODIFY/DELETE 先读活跃元数据：缺失 → {@code DomainException("没有找到元数据:")}，
 *       已失效 → {@code DomainException("数据已经失效:")}，
 *       期望版本不符 → {@link CheckForConcurrencyException}；</li>
 *   <li>元数据更新带 CAS 过滤（{@code invalid=0 AND version=期望版本}），
 *       命中 0 行 → {@link CheckForConcurrencyException}；</li>
 *   <li>DELETE 写回收站（受 {@code operation.isTrash()} 开关控制）。</li>
 * </ul>
 */
public class PgBatchRepository implements IBatchRepository {

    private final PgDao dao;
    private final PgEventSourcingRepository events;
    private final PgTrashRepository trash;
    private final BatchPlanner planner;

    public PgBatchRepository(PgDao dao) {
        if (dao == null) {
            throw new DomainException("PGSQL 批量仓储未配置数据访问对象");
        }
        this.dao = dao;
        this.events = new PgEventSourcingRepository(dao);
        this.trash = new PgTrashRepository(dao);
        this.planner = new BatchPlanner();
    }

    @Override
    public void persistBatch(List<BatchOperation> operations, boolean atomic) {
        if (operations == null || operations.isEmpty()) {
            return;
        }
        BatchPlan plan = planner.plan(operations);
        ensureSnapshotTables(operations);
        if (atomic) {
            try (Connection c = dao.connection()) {
                boolean oldAuto = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    persistPlan(c, plan);
                    c.commit();
                } catch (Exception e) {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {
                    }
                    throw e;
                } finally {
                    try {
                        c.setAutoCommit(oldAuto);
                    } catch (SQLException ignored) {
                    }
                }
            } catch (SQLException e) {
                throw new DomainException("PGSQL 批量事务失败: " + e.getMessage(), e);
            }
        } else {
            try (Connection c = dao.connection()) {
                persistPlan(c, plan);
            } catch (SQLException e) {
                throw new DomainException("PGSQL 批量写入失败: " + e.getMessage(), e);
            }
        }
    }

    private void persistPlan(Connection c, BatchPlan plan) throws SQLException {
        persistAdds(c, plan.getAdds());
        persistModifies(c, plan.getModifies());
        persistDeletes(c, plan.getDeletes());
    }

    private void persistAdds(Connection c, List<BatchOperation> operations) throws SQLException {
        for (BatchOperation operation : operations) {
            DomainModel<?> entity = operation.getEntity();
            String streamName = operation.streamName();
            String table = PgDocumentMapper.snapshotTable(operation.snapshotCollection());
            if (events.loadMetadata(c, streamName) != null
                    || events.snapshotExists(c, table, streamName)) {
                throw new DomainException("实体已经存在:" + streamName);
            }
            if (operation.isSkipEventSourcing()) {
                events.upsertSnapshot(c, table, operation.snapshot());
                continue;
            }
            EventStream stream = new EventStream(streamName, entity.getClass());
            stream.setCreateDate(PgEventSourcingRepository.now());
            appendEvents(c, stream, operation);
            events.insertMetadata(c, stream);
            events.upsertSnapshot(c, table, operation.snapshot());
        }
    }

    private void persistModifies(Connection c, List<BatchOperation> operations) throws SQLException {
        for (BatchOperation operation : operations) {
            String streamName = operation.streamName();
            String table = PgDocumentMapper.snapshotTable(operation.snapshotCollection());
            if (operation.isSkipEventSourcing()) {
                events.upsertSnapshot(c, table, operation.snapshot());
                continue;
            }
            EventStream stream = loadActiveMetadata(c, operation);
            int previousVersion = stream.getVersion();
            appendEvents(c, stream, operation);
            casMetadata(c, operation, stream, previousVersion, 0);
            events.upsertSnapshot(c, table, operation.snapshot());
        }
    }

    private void persistDeletes(Connection c, List<BatchOperation> operations) throws SQLException {
        for (BatchOperation operation : operations) {
            String streamName = operation.streamName();
            String table = PgDocumentMapper.snapshotTable(operation.snapshotCollection());
            Map<String, Object> oldRow = events.findSnapshotRow(c, table, streamName);
            if (operation.isSkipEventSourcing()) {
                addTrash(c, operation);
                events.deleteSnapshot(c, table, streamName);
                continue;
            }
            EventStream stream = loadActiveMetadata(c, operation);
            int previousVersion = stream.getVersion();
            appendEvents(c, stream, operation);
            casMetadata(c, operation, stream, previousVersion, 1);
            // 回收站需要旧快照：先存 trash 再删快照（Mongo 侧同理）
            addTrash(c, operation);
            events.deleteSnapshot(c, table, streamName);
        }
    }

    private EventStream loadActiveMetadata(Connection c, BatchOperation operation) throws SQLException {
        String streamName = operation.streamName();
        EventStream stream = events.loadMetadata(c, streamName);
        if (stream == null) {
            throw new DomainException("没有找到元数据:" + streamName);
        }
        if (stream.getIsInvalid() == 1) {
            throw new DomainException("数据已经失效:" + streamName);
        }
        if (operation.getExpectedVersion() != null
                && !operation.getExpectedVersion().equals(stream.getVersion())) {
            throw new CheckForConcurrencyException(String.format(
                    "预期版本号: %d。 找到的版本号: %d",
                    operation.getExpectedVersion(), stream.getVersion()));
        }
        return stream;
    }

    private void casMetadata(Connection c, BatchOperation operation, EventStream stream,
                             int previousVersion, int invalid) throws SQLException {
        Integer expected = operation.getExpectedVersion() != null
                ? operation.getExpectedVersion() : previousVersion;
        boolean ok = events.updateMetadataCAS(c, operation.streamName(),
                stream.getVersion(), invalid, expected);
        if (!ok) {
            throw new CheckForConcurrencyException("数据更新存在版本冲突或数据已失效");
        }
    }

    private void appendEvents(Connection c, EventStream stream, BatchOperation operation) throws SQLException {
        for (DomainEvent event : operation.getEvents()) {
            EventWrapper wrapper = stream.registerEvent(event, operation.getEntity().getClass());
            events.insertEvent(c, wrapper);
        }
    }

    private void addTrash(Connection c, BatchOperation operation) throws SQLException {
        if (operation.isTrash()) {
            trash.save(c, operation.getEntity().getClass(),
                    operation.streamName(), operation.getEntity().getId());
        }
    }

    private void ensureSnapshotTables(List<BatchOperation> operations) {
        for (BatchOperation operation : operations) {
            dao.ensureSnapshotTable(PgDocumentMapper.snapshotTable(operation.snapshotCollection()));
            SnapshotWrapper snapshot = operation.snapshot();
            if (snapshot != null && snapshot.getSnapshotType() != null) {
                dao.ensureSnapshotTable(PgDocumentMapper.snapshotTable(snapshot.getSnapshotType()));
            }
        }
    }
}
