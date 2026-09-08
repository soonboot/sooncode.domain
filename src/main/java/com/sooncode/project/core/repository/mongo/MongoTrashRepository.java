package com.sooncode.project.core.repository.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.sooncode.project.core.annotations.ModelSnapshot;
import com.sooncode.project.core.trash.ITrashRepository;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * MongoDB Trash 存储库实现。
 *
 * <p>集合名为 {@code trash}，删除实体时保存完整 snapshot doc，恢复时写回原快照集合。</p>
 */
public class MongoTrashRepository implements ITrashRepository {
    private static final String COLLECTION_NAME = "trash";
    private static final String DEFAULT_SNAPSHOT_COLLECTION = "eventSnapshot";

    private final IMongoDBDao dao;
    private final String dbName;

    public MongoTrashRepository(IMongoDBDao dao, String dbName) {
        this.dao = dao;
        this.dbName = dbName;
    }

    /**
     * 使用框架默认数据库连接（MongoSingle 单例）创建 Trash 存储库。
     *
     * @return Mongo Trash 存储库；若 MongoSingle 未初始化（未调用 new MongoConnection），返回 null
     */
    public static MongoTrashRepository fromDefault() {
        MongoSingle single = MongoSingle.getInstance();
        if (single == null || single.mongoDB == null || single.dbName == null || single.dbName.isEmpty()) {
            return null;
        }
        return new MongoTrashRepository(single.mongoDB, single.dbName);
    }

    private MongoCollection<Document> getCollection() {
        return dao.getCollection(dbName, COLLECTION_NAME);
    }

    @Override
    public void save(Class<?> entityClass, String streamId, String entityId) {
        String snapshotCollection = resolveSnapshotCollection(entityClass);
        MongoCollection<Document> snapshotCol = dao.getCollection(dbName, snapshotCollection);
        Document snapshotDoc = snapshotCol.find(Filters.eq("streamId", streamId)).first();
        if (snapshotDoc == null) return;

        MongoCollection<Document> col = getCollection();
        Document doc = new Document();
        doc.put("streamId", streamId);
        doc.put("entityId", entityId);
        doc.put("entityType", entityClass.getName());
        doc.put("snapshotDoc", snapshotDoc);
        doc.put("deleteTime", new Date());
        col.insertOne(doc);
    }

    @Override
    public Document findByStreamId(String streamId) {
        MongoCollection<Document> col = getCollection();
        return col.find(Filters.eq("streamId", streamId)).first();
    }

    @Override
    public Document restoreSnapshot(String streamId, Class<?> entityClass) {
        Document trashDoc = findByStreamId(streamId);
        if (trashDoc == null) return null;

        Document snapshotDoc = (Document) trashDoc.get("snapshotDoc");
        String collectionName = resolveSnapshotCollection(entityClass);
        MongoCollection<Document> col = dao.getCollection(dbName, collectionName);
        col.insertOne(snapshotDoc);

        removeByStreamId(streamId);
        return snapshotDoc;
    }

    @Override
    public List<Map<String, Object>> listByEntityType(String entityType, int pageIndex, int pageSize) {
        MongoCollection<Document> col = getCollection();
        Bson filter = Filters.eq("entityType", entityType);
        return new ArrayList<Map<String, Object>>(col.find(filter)
                .sort(new Document("deleteTime", -1))
                .skip(pageIndex * pageSize)
                .limit(pageSize)
                .into(new ArrayList<Document>()));
    }

    @Override
    public long countByEntityType(String entityType) {
        MongoCollection<Document> col = getCollection();
        return col.countDocuments(Filters.eq("entityType", entityType));
    }

    @Override
    public List<Map<String, Object>> listAll(int pageIndex, int pageSize) {
        MongoCollection<Document> col = getCollection();
        return new ArrayList<Map<String, Object>>(col.find()
                .sort(new Document("deleteTime", -1))
                .skip(pageIndex * pageSize)
                .limit(pageSize)
                .into(new ArrayList<Document>()));
    }

    @Override
    public long countAll() {
        MongoCollection<Document> col = getCollection();
        return col.countDocuments();
    }

    @Override
    public void removeByStreamId(String streamId) {
        MongoCollection<Document> col = getCollection();
        col.deleteMany(Filters.eq("streamId", streamId));
    }

    private String resolveSnapshotCollection(Class<?> entityClass) {
        ModelSnapshot ann = entityClass.getAnnotation(ModelSnapshot.class);
        if (ann != null) {
            String name = ann.value();
            if (name != null && !name.isEmpty()) return name;
            name = ann.collectionName();
            if (name != null && !name.isEmpty()) return name;
        }
        return DEFAULT_SNAPSHOT_COLLECTION;
    }
}
