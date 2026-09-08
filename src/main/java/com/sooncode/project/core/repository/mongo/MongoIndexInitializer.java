package com.sooncode.project.core.repository.mongo;

import com.sooncode.project.core.model.DomainException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一初始化事件存储使用的 MongoDB 索引。
 *
 * <p>MongoDB 对相同 key pattern 但不同 options 的索引会抛出
 * {@code IndexOptionsConflict}。单条仓储和批量仓储必须经过同一个入口
 * 创建索引，避免两套实现使用不同的 unique 配置。</p>
 */
final class MongoIndexInitializer {
    private static final Document METADATA_ID_KEY = new Document(MongoDocumentMapper.ID, 1);
    private static final Document EVENT_STREAM_VERSION_KEY = new Document()
            .append(MongoDocumentMapper.STREAM_ID, 1)
            .append(MongoDocumentMapper.VERSION, 1);
    private static final Document SNAPSHOT_STREAM_ID_KEY = new Document(MongoDocumentMapper.STREAM_ID, 1);
    private static final Document SNAPSHOT_TYPE_KEY = new Document(MongoDocumentMapper.SNAPSHOT_TYPE, 1);
    private static final Document SNAPSHOT_CREATE_DATE_KEY = new Document(MongoDocumentMapper.CREATE_DATE, -1);
    private static final Document TRASH_STREAM_ID_KEY = new Document(MongoDocumentMapper.STREAM_ID, 1);

    private MongoIndexInitializer() {
    }

    static void initializeEventMetadata(MongoCollection<Document> collection) {
        ensureIndex(collection, METADATA_ID_KEY, true, "event_metadata_id_unique");
    }

    static void initializeCoreIndexes(IMongoDBDao dao, String dbName) {
        initializeEventMetadata(dao.getCollection(dbName, MongoDocumentMapper.EVENT_METADATA));
        initializeEventSource(dao.getCollection(dbName, MongoDocumentMapper.EVENT_SOURCE));
        initializeSnapshot(dao.getCollection(dbName, MongoDocumentMapper.EVENT_SNAPSHOT));
        initializeTrash(dao.getCollection(dbName, "trash"));
    }

    static void initializeEventSource(MongoCollection<Document> collection) {
        ensureIndex(collection, EVENT_STREAM_VERSION_KEY, true, "event_source_stream_version_unique");
    }

    static void initializeSnapshot(MongoCollection<Document> collection) {
        ensureIndex(collection, SNAPSHOT_STREAM_ID_KEY, true, "snapshot_stream_id_unique");
        ensureIndex(collection, SNAPSHOT_TYPE_KEY, false, "snapshot_type");
        ensureIndex(collection, SNAPSHOT_CREATE_DATE_KEY, false, "snapshot_create_date");
    }

    static void initializeTrash(MongoCollection<Document> collection) {
        ensureIndex(collection, TRASH_STREAM_ID_KEY, false, "trash_stream_id");
        ensureIndex(collection, new Document("entityType", 1), false, "trash_entity_type");
        ensureIndex(collection, new Document("deleteTime", -1), false, "trash_delete_time");
    }

    /**
     * 确保 key pattern 对应的索引配置一致。
     *
     * <p>对于已经存在的旧非唯一索引，只有在确认没有重复键后才重建为唯一索引；
     * 如果历史数据存在重复值，则保留原索引并抛出明确异常，避免静默丢失索引或数据。</p>
     */
    private static synchronized void ensureIndex(MongoCollection<Document> collection,
                                                   Document key,
                                                   boolean unique,
                                                   String indexName) {
        for (Document existing : collection.listIndexes().into(new ArrayList<>())) {
            Document existingKey = existing.get("key", Document.class);
            if (!key.equals(existingKey)) continue;

            boolean existingUnique = Boolean.TRUE.equals(existing.getBoolean("unique", false));
            if (existingUnique == unique) return;

            // 永远不把已经存在的唯一索引降级为普通索引。
            if (existingUnique && !unique) return;

            if (unique) {
                List<Document> duplicates = findDuplicateKeys(collection, key);
                if (!duplicates.isEmpty()) {
                    throw duplicateIndexException(collection, key, "升级", duplicates);
                }
            }
            String existingName = existing.getString("name");
            if (existingName != null && !existingName.isEmpty()) {
                collection.dropIndex(existingName);
            }
            break;
        }

        if (unique) {
            List<Document> duplicates = findDuplicateKeys(collection, key);
            if (!duplicates.isEmpty()) {
                throw duplicateIndexException(collection, key, "创建", duplicates);
            }
        }
        collection.createIndex(key, new IndexOptions().name(indexName).unique(unique));
    }

    private static List<Document> findDuplicateKeys(MongoCollection<Document> collection, Document key) {
        Document groupKey = new Document();
        for (String field : key.keySet()) {
            groupKey.put(field, "$" + field);
        }

        List<Document> pipeline = List.of(
                new Document("$group", new Document("_id", groupKey)
                        .append("count", new Document("$sum", 1))
                        .append("documentIds", new Document("$push", "$_id"))),
                new Document("$match", new Document("count", new Document("$gt", 1))),
                new Document("$limit", 5)
        );
        return collection.aggregate(pipeline).into(new ArrayList<>());
    }

    private static DomainException duplicateIndexException(MongoCollection<Document> collection,
                                                            Document key,
                                                            String action,
                                                            List<Document> duplicates) {
        String namespace = collection.getNamespace().getFullName();
        return new DomainException(String.format(
                "Mongo索引无法%s为唯一索引，数据库集合:%s，索引键:%s，重复数据(最多5组):%s",
                action,
                namespace,
                key.toJson(),
                duplicates
        ));
    }
}
