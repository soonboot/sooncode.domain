package com.sooncode.project.core.recycle;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.sooncode.project.core.annotations.ModelSnapshot;
import com.sooncode.project.core.repository.mongo.IMongoDBDao;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class RecycleBinRepository {
    private static final String COLLECTION_NAME = "recycleBin";
    private static final String DEFAULT_SNAPSHOT_COLLECTION = "eventSnapshot";

    private final IMongoDBDao dao;
    private final String dbName;
    private boolean indexesCreated = false;

    public RecycleBinRepository(IMongoDBDao dao, String dbName) {
        this.dao = dao;
        this.dbName = dbName;
    }

    private MongoCollection<Document> getCollection() {
        MongoCollection<Document> col = dao.getCollection(dbName, COLLECTION_NAME);
        if (!indexesCreated) {
            col.createIndex(Indexes.ascending("streamId"));
            col.createIndex(Indexes.ascending("entityType"));
            col.createIndex(Indexes.descending("deleteTime"));
            indexesCreated = true;
        }
        return col;
    }

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

    public Document findByStreamId(String streamId) {
        MongoCollection<Document> col = getCollection();
        return col.find(Filters.eq("streamId", streamId)).first();
    }

    public Document restoreSnapshot(String streamId, Class<?> entityClass) {
        Document recycleDoc = findByStreamId(streamId);
        if (recycleDoc == null) return null;

        Document snapshotDoc = (Document) recycleDoc.get("snapshotDoc");
        String collectionName = resolveSnapshotCollection(entityClass);
        MongoCollection<Document> col = dao.getCollection(dbName, collectionName);
        col.insertOne(snapshotDoc);

        removeByStreamId(streamId);
        return snapshotDoc;
    }

    public List<Document> listByEntityType(String entityType, int pageIndex, int pageSize) {
        MongoCollection<Document> col = getCollection();
        Bson filter = Filters.eq("entityType", entityType);
        return col.find(filter)
                .sort(new Document("deleteTime", -1))
                .skip(pageIndex * pageSize)
                .limit(pageSize)
                .into(new ArrayList<>());
    }

    public long countByEntityType(String entityType) {
        MongoCollection<Document> col = getCollection();
        return col.countDocuments(Filters.eq("entityType", entityType));
    }

    public List<Document> listAll(int pageIndex, int pageSize) {
        MongoCollection<Document> col = getCollection();
        return col.find()
                .sort(new Document("deleteTime", -1))
                .skip(pageIndex * pageSize)
                .limit(pageSize)
                .into(new ArrayList<>());
    }

    public long countAll() {
        MongoCollection<Document> col = getCollection();
        return col.countDocuments();
    }

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
