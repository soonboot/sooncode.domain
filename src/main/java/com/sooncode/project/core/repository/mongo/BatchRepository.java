package com.sooncode.project.core.repository.mongo;

import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.TransactionBody;
import com.mongodb.client.model.DeleteOneModel;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.InsertOneModel;
import com.mongodb.client.model.UpdateOneModel;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.WriteModel;
import com.sooncode.project.core.batcher.BatchPlan;
import com.sooncode.project.core.batcher.BatchOperation;
import com.sooncode.project.core.batcher.BatchPlanner;
import com.sooncode.project.core.batcher.IBatchRepository;
import com.sooncode.project.core.model.CheckForConcurrencyException;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.EventStream;
import com.sooncode.project.core.model.EventWrapper;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** MongoDB 批量持久化仓储，负责 bulk 写入和事务边界。 */
public class BatchRepository implements IBatchRepository {
    private final IMongoDBDao dao;
    private final String dbName;
    private final BatchPlanner planner;
    private final Set<String> initializedSnapshotCollections = ConcurrentHashMap.newKeySet();

    public BatchRepository(IMongoDBDao dao, String dbName) {
        if (dao == null) throw new DomainException("Mongo批量仓储未配置数据访问对象");
        this.dao = dao;
        this.dbName = dbName;
        this.planner = new BatchPlanner();
        initializedSnapshotCollections.add(MongoDocumentMapper.EVENT_SNAPSHOT);
    }

    @Override
    public void persistBatch(List<BatchOperation> operations, boolean atomic) {
        if (operations == null || operations.isEmpty()) return;
        if (!(dao instanceof MongoDBImpl)) {
            throw new DomainException("Mongo批量持久化需要MongoDBImpl数据访问实现");
        }
        MongoDBImpl mongoDao = (MongoDBImpl) dao;
        BatchPlan plan = planner.plan(operations);
        ensureCustomSnapshotIndexes(operations);
        ClientSession session = mongoDao.getClient(dbName).startSession();
        try {
            BatchContext context = new BatchContext(session);
            if (atomic) {
                session.withTransaction((TransactionBody<Void>) () -> {
                    persistPlan(plan, context);
                    return null;
                }, TransactionOptions.builder().writeConcern(WriteConcern.MAJORITY).build());
            } else {
                persistPlan(plan, context);
            }
        } finally {
            session.close();
        }
    }

    private void persistPlan(BatchPlan plan, BatchContext context) {
        persistAdds(plan.getAdds(), context);
        persistModifies(plan.getModifies(), context);
        persistDeletes(plan.getDeletes(), context);
        flush(context);
    }

    private void persistAdds(List<BatchOperation> operations, BatchContext context) {
        for (BatchOperation operation : operations) {
            DomainModel entity = operation.getEntity();
            String streamName = operation.streamName();
            String snapshotCollection = operation.snapshotCollection();
            List<WriteModel<Document>> snapshotWrites = context.snapshotWrites(snapshotCollection);
            if (loadMetadata(context.session, streamName) != null
                    || findSnapshot(context.session, snapshotCollection, streamName) != null) {
                throw new DomainException("实体已经存在:" + streamName);
            }

            if (operation.isSkipEventSourcing()) {
                snapshotWrites.add(new InsertOneModel<>(MongoDocumentMapper.snapshot(operation.snapshot())));
                continue;
            }

            EventStream stream = new EventStream(streamName, entity.getClass());
            stream.setCreateDate(new Date());
            appendEventDocuments(stream, operation, context.eventWrites);
            context.metadataWrites.add(new InsertOneModel<>(MongoDocumentMapper.metadata(stream)));
            snapshotWrites.add(new InsertOneModel<>(MongoDocumentMapper.snapshot(operation.snapshot())));
        }
    }

    private void persistModifies(List<BatchOperation> operations, BatchContext context) {
        for (BatchOperation operation : operations) {
            DomainModel entity = operation.getEntity();
            String streamName = operation.streamName();
            String snapshotCollection = operation.snapshotCollection();
            List<WriteModel<Document>> snapshotWrites = context.snapshotWrites(snapshotCollection);
            if (operation.isSkipEventSourcing()) {
                snapshotWrites.add(new UpdateOneModel<>(Filters.eq(MongoDocumentMapper.STREAM_ID, streamName),
                        new Document("$set", MongoDocumentMapper.snapshotFields(operation.snapshot())),
                        new UpdateOptions().upsert(true)));
                continue;
            }

            EventStream stream = loadActiveMetadata(context.session, operation);
            int previousVersion = stream.getVersion();
            appendEventDocuments(stream, operation, context.eventWrites);
            context.metadataWrites.add(metadataUpdate(operation, stream, previousVersion));
            snapshotWrites.add(new UpdateOneModel<>(Filters.eq(MongoDocumentMapper.STREAM_ID, streamName),
                    new Document("$set", MongoDocumentMapper.snapshotFields(operation.snapshot())),
                    new UpdateOptions().upsert(true)));
        }
    }

    private void persistDeletes(List<BatchOperation> operations, BatchContext context) {
        for (BatchOperation operation : operations) {
            DomainModel entity = operation.getEntity();
            String streamName = operation.streamName();
            String snapshotCollection = operation.snapshotCollection();
            List<WriteModel<Document>> snapshotWrites = context.snapshotWrites(snapshotCollection);
            if (operation.isSkipEventSourcing()) {
                Document oldSnapshot = findSnapshot(context.session, snapshotCollection, streamName);
                addTrash(operation, oldSnapshot, context);
                snapshotWrites.add(new DeleteOneModel<>(Filters.eq(MongoDocumentMapper.STREAM_ID, streamName)));
                continue;
            }

            EventStream stream = loadActiveMetadata(context.session, operation);
            int previousVersion = stream.getVersion();
            appendEventDocuments(stream, operation, context.eventWrites);
            context.metadataWrites.add(metadataUpdate(operation, stream, previousVersion));
            Document oldSnapshot = findSnapshot(context.session, snapshotCollection, streamName);
            addTrash(operation, oldSnapshot, context);
            snapshotWrites.add(new DeleteOneModel<>(Filters.eq(MongoDocumentMapper.STREAM_ID, streamName)));
        }
    }

    private EventStream loadActiveMetadata(ClientSession session, BatchOperation operation) {
        String streamName = operation.streamName();
        EventStream stream = loadMetadata(session, streamName);
        if (stream == null) throw new DomainException("没有找到元数据:" + streamName);
        if (stream.getIsInvalid() == 1) throw new DomainException("数据已经失效:" + streamName);
        if (operation.getExpectedVersion() != null
                && !operation.getExpectedVersion().equals(stream.getVersion())) {
            throw new CheckForConcurrencyException(String.format(
                    "预期版本号: %d。 找到的版本号: %d",
                    operation.getExpectedVersion(), stream.getVersion()));
        }
        return stream;
    }

    private UpdateOneModel<Document> metadataUpdate(BatchOperation operation,
                                                      EventStream stream,
                                                      int previousVersion) {
        Bson metadataFilter = metadataFilter(operation.streamName(),
                operation.getExpectedVersion(), previousVersion);
        Map<String, Object> metadataUpdate = new HashMap<>();
        metadataUpdate.put("version", stream.getVersion());
        metadataUpdate.put("invalid", operation.getType() == BatchOperation.Type.DELETE ? 1 : 0);
        return new UpdateOneModel<>(metadataFilter,
                new Document("$set", new Document(metadataUpdate)));
    }

    private void addTrash(BatchOperation operation, Document oldSnapshot, BatchContext context) {
        if (operation.isTrash() && oldSnapshot != null) {
            context.trashWrites.add(trashDocument(operation.getEntity(), operation.streamName(), oldSnapshot));
        }
    }

    private void flush(BatchContext context) {
        if (!context.metadataWrites.isEmpty()) {
            int updateCount = 0;
            for (WriteModel<Document> write : context.metadataWrites) {
                if (write instanceof UpdateOneModel) updateCount++;
            }
            BulkWriteResult result = context.metadata.bulkWrite(context.session, context.metadataWrites);
            if (result.getMatchedCount() < updateCount) {
                throw new CheckForConcurrencyException("批量更新存在版本冲突或数据已失效");
            }
        }
        if (!context.eventWrites.isEmpty()) context.source.insertMany(context.session, context.eventWrites);
        if (!context.trashWrites.isEmpty()) trashCollection().insertMany(context.session, context.trashWrites);
        for (Map.Entry<String, List<WriteModel<Document>>> entry : context.snapshots.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                dao.getCollection(dbName, entry.getKey()).bulkWrite(context.session, entry.getValue());
            }
        }
    }

    private void ensureCustomSnapshotIndexes(List<BatchOperation> operations) {
        Set<String> snapshotCollections = new java.util.HashSet<>();
        for (BatchOperation operation : operations) {
            String collectionName = operation.snapshotCollection();
            if (snapshotCollections.add(collectionName)) ensureSnapshotIndexes(collectionName);
        }
    }

    private void ensureSnapshotIndexes(String collectionName) {
        synchronized (initializedSnapshotCollections) {
            if (initializedSnapshotCollections.contains(collectionName)) return;
            MongoIndexInitializer.initializeSnapshot(dao.getCollection(dbName, collectionName));
            initializedSnapshotCollections.add(collectionName);
        }
    }

    private EventStream loadMetadata(ClientSession session, String streamName) {
        Document doc = dao.getCollection(dbName, MongoDocumentMapper.EVENT_METADATA)
                .find(session, Filters.eq(MongoDocumentMapper.ID, streamName)).first();
        if (doc == null) return null;
        try {
            return MongoDocumentMapper.toEventStream(doc);
        } catch (Exception ex) {
            throw new DomainException("读取元数据失败:" + streamName);
        }
    }

    private void appendEventDocuments(EventStream stream, BatchOperation operation, List<Document> target) {
        for (DomainEvent event : operation.getEvents()) {
            EventWrapper wrapper = stream.registerEvent(event, operation.getEntity().getClass());
            target.add(MongoDocumentMapper.event(wrapper));
        }
    }

    private Bson metadataFilter(String streamName, Integer expectedVersion, int currentVersion) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq(MongoDocumentMapper.ID, streamName));
        filters.add(Filters.eq(MongoDocumentMapper.INVALID, 0));
        if (expectedVersion != null) filters.add(Filters.eq(MongoDocumentMapper.VERSION, expectedVersion));
        else filters.add(Filters.eq(MongoDocumentMapper.VERSION, currentVersion));
        return Filters.and(filters);
    }

    private Document findSnapshot(ClientSession session, String collectionName, String streamName) {
        return dao.getCollection(dbName, collectionName)
                .find(session, Filters.eq(MongoDocumentMapper.STREAM_ID, streamName)).first();
    }

    private Document trashDocument(DomainModel entity, String streamName, Document snapshot) {
        return new Document("streamId", streamName)
                .append("entityId", entity.getId())
                .append("entityType", entity.getClass().getName())
                .append("snapshotDoc", snapshot)
                .append("deleteTime", new Date());
    }

    private MongoCollection<Document> trashCollection() {
        return dao.getCollection(dbName, "trash");
    }

    private final class BatchContext {
        private final ClientSession session;
        private final MongoCollection<Document> metadata;
        private final MongoCollection<Document> source;
        private final Map<String, List<WriteModel<Document>>> snapshots = new LinkedHashMap<>();
        private final List<WriteModel<Document>> metadataWrites = new ArrayList<>();
        private final List<Document> eventWrites = new ArrayList<>();
        private final List<Document> trashWrites = new ArrayList<>();

        private BatchContext(ClientSession session) {
            this.session = session;
            this.metadata = dao.getCollection(dbName, MongoDocumentMapper.EVENT_METADATA);
            this.source = dao.getCollection(dbName, MongoDocumentMapper.EVENT_SOURCE);
        }

        private List<WriteModel<Document>> snapshotWrites(String collectionName) {
            return snapshots.computeIfAbsent(collectionName, key -> new ArrayList<>());
        }
    }
}
