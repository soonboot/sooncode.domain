package com.sooncode.project.core.repository.mongo;

import com.mongodb.MongoBulkWriteException;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.BulkWriteOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import com.sooncode.project.core.annotations.ModelSnapshot;
import com.sooncode.project.core.model.Entity;
import com.sooncode.project.core.model.SnapshotWrapper;
import com.sooncode.project.core.config.InfraConfig;
import com.sooncode.project.core.lookup.ILookupBulkWriter;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lookup 专用的 Mongo 批量写入实现，不走 {@code Batcher}。
 * <p>
 * 语义：BEST_EFFORT 的 {@code bulkWrite(ReplaceOne upsert, ordered:false)}，
 * 直接操作快照集合，绕过事件、CAS、事务、Notice，与业务批量彻底隔离。
 */
public class MongoLookupBulkWriter implements ILookupBulkWriter {

    private static final Logger log = LoggerFactory.getLogger(MongoLookupBulkWriter.class);
    private static final int DEFAULT_BATCH_SIZE = 500;

    private final IMongoDBDao dao;
    private final String dbName;
    private volatile int batchSize;
    private static final Set<String> initializedSnapshotCollections = ConcurrentHashMap.newKeySet();

    public MongoLookupBulkWriter(IMongoDBDao dao, String dbName) {
        this(dao, dbName, DEFAULT_BATCH_SIZE);
    }

    public MongoLookupBulkWriter(IMongoDBDao dao, String dbName, int batchSize) {
        if (dao == null) throw new IllegalArgumentException("MongoLookupBulkWriter: dao 不能为空");
        if (dbName == null || dbName.isEmpty()) throw new IllegalArgumentException("MongoLookupBulkWriter: dbName 不能为空");
        this.dao = dao;
        this.dbName = dbName;
        this.batchSize = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        initializedSnapshotCollections.add(MongoDocumentMapper.EVENT_SNAPSHOT);
    }

    /**
     * P1-5 动态刷新：重读 InfraConfig.getLookupBulkBatchSize()。
     * Monitor.set* 后经 Handler.refreshConfig() 触发，下一次 bulkSaveSnapshots 即按新值分片，无需重建。
     */
    @Override
    public void refresh() {
        int n = InfraConfig.getLookupBulkBatchSize();
        if (n > 0) this.batchSize = n;
    }

    @Override
    public int bulkSaveSnapshots(Class<?> modelType, List<Entity> entities) {
        if (entities == null || entities.isEmpty()) return 0;
        if (modelType == null) throw new IllegalArgumentException("modelType 不能为空");
        String collectionName = getCollectionName(modelType);
        ensureSnapshotIndexes(collectionName);
        MongoCollection<Document> col = dao.getCollection(dbName, collectionName);
        if (col == null) {
            log.error("[Lookup][Bulk] 获取集合失败 db={} collection={}", dbName, collectionName);
            return 0;
        }
        // P1-5 每次调用重读 InfraConfig，Monitor.setLookupBulkBatchSize 后即时生效（refresh() 同步字段值，本处兜底直读）
        int effectiveBatch = InfraConfig.getLookupBulkBatchSize() > 0 ? InfraConfig.getLookupBulkBatchSize() : batchSize;
        if (effectiveBatch <= 0) effectiveBatch = DEFAULT_BATCH_SIZE;
        int totalSuccess = 0;
        for (int start = 0; start < entities.size(); start += effectiveBatch) {
            int end = Math.min(start + effectiveBatch, entities.size());
            List<Entity> slice = entities.subList(start, end);
            List<WriteModel<Document>> writes = new ArrayList<>(slice.size());
            for (Entity entity : slice) {
                if (entity == null || entity.getId() == null) {
                    log.warn("[Lookup][Bulk] 跳过空实体或空 id, modelType={}", modelType.getSimpleName());
                    continue;
                }
                String streamId = streamNameFor(modelType, entity.getId());
                SnapshotWrapper wrapper = new SnapshotWrapper(streamId, entity);
                Document doc = MongoDocumentMapper.snapshot(wrapper);
                Bson filter = Filters.eq(MongoDocumentMapper.STREAM_ID, streamId);
                writes.add(new ReplaceOneModel<>(filter, doc, new ReplaceOptions().upsert(true)));
            }
            if (writes.isEmpty()) continue;
            try {
                BulkWriteResult result = col.bulkWrite(writes, new BulkWriteOptions().ordered(false));
                // ordered:false 且全部为 ReplaceOne upsert时，无异常即视为 slice 全部成功
                // BulkWriteResult 的 matched/upserted/modified 统计在不同驱动版本差异较大，保守按 slice 成功计数
                totalSuccess += slice.size();
                log.debug("[Lookup][Bulk] 批量写入成功 collection={} slice={} totalSuccess={}", collectionName, slice.size(), totalSuccess);
            } catch (MongoBulkWriteException bwe) {
                BulkWriteResult r = bwe.getWriteResult();
                int sliceSuccess = 0;
                if (r != null) {
                    sliceSuccess = r.getInsertedCount() + r.getMatchedCount() + r.getUpserts().size();
                    // modifiedCount 在 4.x 可能单独统计，但 matched 已包含 modified，按保守不重复加
                    // 若驱动提供了 getModifiedCount 则可能大于 matched，取最大者的思路避免漏计
                    try {
                        int modified = r.getModifiedCount();
                        if (modified > sliceSuccess) sliceSuccess = modified;
                    } catch (Exception ignore) {}
                }
                // 兜底：若驱动未准确统计，则按 (slice.size - errors) 估算
                int errors = bwe.getWriteErrors() != null ? bwe.getWriteErrors().size() : 0;
                if (sliceSuccess == 0 && errors < slice.size()) {
                    sliceSuccess = slice.size() - errors;
                }
                totalSuccess += sliceSuccess;
                log.warn("[Lookup][Bulk] 批量部分失败 collection={} slice={} 成功 {} 失败 {} 错误: {}", collectionName, slice.size(), sliceSuccess, errors, bwe.getMessage());
                for (com.mongodb.bulk.BulkWriteError err : bwe.getWriteErrors()) {
                    log.warn("[Lookup][Bulk] 写入错误 index={} code={} msg={}", err.getIndex(), err.getCode(), err.getMessage());
                }
            } catch (Exception e) {
                log.error("[Lookup][Bulk] 批量写入异常 collection={} slice={}: {}", collectionName, slice.size(), e.getMessage(), e);
                // 本批视为全部失败，不计入 totalSuccess，由上层决定是否逐条 fallback
            }
        }
        if (totalSuccess < entities.size()) {
            log.warn("[Lookup][Bulk] 批量完成但存在失败 modelType={} collection={} 请求 {} 成功 {}", modelType.getSimpleName(), collectionName, entities.size(), totalSuccess);
        } else {
            log.info("[Lookup][Bulk] 批量完成 modelType={} collection={} 成功 {}/{}", modelType.getSimpleName(), collectionName, totalSuccess, entities.size());
        }
        return totalSuccess;
    }

    private String getCollectionName(Class<?> cType) {
        String collectionName = "";
        if (cType.isAnnotationPresent(ModelSnapshot.class)) {
            ModelSnapshot modelSnapshot = cType.getAnnotation(ModelSnapshot.class);
            collectionName = modelSnapshot.value();
            if (collectionName == null || collectionName.isEmpty()) {
                collectionName = modelSnapshot.collectionName();
            }
        }
        if (collectionName == null || collectionName.isEmpty()) return MongoDocumentMapper.EVENT_SNAPSHOT;
        return collectionName;
    }

    private String streamNameFor(Class<?> c, String id) {
        return c.getName() + "-" + id;
    }

    private void ensureSnapshotIndexes(String collectionName) {
        synchronized (MongoLookupBulkWriter.class) {
            if (initializedSnapshotCollections.contains(collectionName)) return;
            MongoIndexInitializer.initializeSnapshot(dao.getCollection(dbName, collectionName));
            initializedSnapshotCollections.add(collectionName);
        }
    }
}
