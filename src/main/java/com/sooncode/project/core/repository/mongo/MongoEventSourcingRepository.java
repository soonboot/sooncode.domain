package com.sooncode.project.core.repository.mongo;

import com.alibaba.fastjson.JSONObject;
import com.mongodb.BasicDBObject;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.model.*;
import com.sooncode.project.core.utils.Utils;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MongoDB数据库的事件溯源存储库实体类
 */
public class MongoEventSourcingRepository implements IEventSourcingRepository {
    private IMongoDBDao dao;
    private String dbName;
    private final Set<String> initializedSnapshotCollections = ConcurrentHashMap.newKeySet();
    public static final int VER = 15;

    @Deprecated
    public MongoEventSourcingRepository(String host, int port, String dbName) {
        MongoConnection mongoConnection = new MongoConnection(host, port, dbName,this);
        this.dao = MongoSingle.getInstance().mongoDB;
        this.dbName = dbName;
    }
    @Deprecated
    public MongoEventSourcingRepository(String host, int port, String user, String password, String dbName) {
        MongoConnection mongoConnection = new MongoConnection(host, port,dbName, user, password, this);
        this.dao = MongoSingle.getInstance().mongoDB;
        this.dbName = dbName;
    }

    MongoEventSourcingRepository(IMongoDBDao dao,String dbName) {
        this.dao = dao;
        this.dbName = dbName;
        initializedSnapshotCollections.add(MongoDocumentMapper.EVENT_SNAPSHOT);
    }

    // P0-4 暴露给 LookupHandler 的 BulkWriter 构造，避免反射私有字段
    public IMongoDBDao getDao() { return dao; }
    public String getDbName() { return dbName; }

    ;

    @Override
    public void addMetadata(EventStream stream) {
        MongoCollection<Document> col = dao.getCollection(dbName, MongoDocumentMapper.EVENT_METADATA);
        dao.addOne(col, MongoDocumentMapper.metadata(stream));
    }

    @Override
    public void updateMetadata(EventStream stream) {
        // 兼容旧路径：直接按 id 更新（非 CAS）。单条事务路径已改为走批量仓储的 CAS，此方法仅作回退
        MongoCollection<Document> col = dao.getCollection(dbName, MongoDocumentMapper.EVENT_METADATA);
        Map<String, Object> map = MongoDocumentMapper.metadataFields(stream);
        BasicDBObject bson = new BasicDBObject();
        bson.put(MongoDocumentMapper.ID, stream.getId());
        dao.update(col, bson, map);
    }

    @Override
    public boolean updateMetadataCAS(EventStream stream, Integer expectedVersion) {
        MongoCollection<Document> col = dao.getCollection(dbName, MongoDocumentMapper.EVENT_METADATA);
        Map<String, Object> map = MongoDocumentMapper.metadataFields(stream);
        // 期望版本为 null 时按当前 DB 版本做校验由调用方保证已检查；此处按 expectedVersion 精确过滤
        org.bson.conversions.Bson filter;
        if (expectedVersion != null) {
            filter = com.mongodb.client.model.Filters.and(
                    com.mongodb.client.model.Filters.eq(MongoDocumentMapper.ID, stream.getId()),
                    com.mongodb.client.model.Filters.eq(MongoDocumentMapper.VERSION, expectedVersion),
                    com.mongodb.client.model.Filters.eq(MongoDocumentMapper.INVALID, 0));
        } else {
            filter = com.mongodb.client.model.Filters.and(
                    com.mongodb.client.model.Filters.eq(MongoDocumentMapper.ID, stream.getId()),
                    com.mongodb.client.model.Filters.eq(MongoDocumentMapper.INVALID, 0));
        }
        com.mongodb.client.result.UpdateResult result = col.updateOne(filter, new Document("$set", new Document(map)));
        if (result.getMatchedCount() == 0) {
            throw new com.sooncode.project.core.model.CheckForConcurrencyException(
                    String.format("元数据 CAS 更新失败，预期版本: %s，流: %s", expectedVersion, stream.getId()));
        }
        return true;
    }

    @Override
    public void saveStream(EventWrapper stream) {
        MongoCollection<Document> col = dao.getCollection(dbName, MongoDocumentMapper.EVENT_SOURCE);
        dao.addOne(col, MongoDocumentMapper.event(stream));
    }

    @Override
    public EventStream loadMetadata(String streamName) {
        MongoCollection<Document> col = dao.getCollection(dbName, MongoDocumentMapper.EVENT_METADATA);
        BasicDBObject bson = new BasicDBObject();
        bson.put(MongoDocumentMapper.ID, streamName);
        MongoCursor<Document> cursor = dao.find(col, bson, null);
        while (cursor.hasNext()) {
            Document doc = cursor.next();
            try {
                return MongoDocumentMapper.toEventStream(doc);
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }
        }
        return null;
    }

    @Override
    public List<EventWrapper> getStream(String streamName, Integer fromVersion, Integer toVersion) {
        MongoCollection<Document> col = dao.getCollection(dbName, MongoDocumentMapper.EVENT_SOURCE);
        List<Bson> list = new ArrayList<>();
        list.add(Filters.eq(MongoDocumentMapper.STREAM_ID, streamName));
        list.add(Filters.gte(MongoDocumentMapper.VERSION, fromVersion));
        list.add(Filters.lte(MongoDocumentMapper.VERSION, toVersion));
        LinkedHashMap<String, IMongoDBDao.SortEnum> sort = new LinkedHashMap<>();
        sort.put("version", IMongoDBDao.SortEnum.ASC);
        Bson bson = Filters.and(list);
        MongoCursor<Document> cursor = dao.find(col, bson, sort);

        List<EventWrapper> events = new ArrayList<>();
        while (cursor.hasNext()) {
            Document doc = cursor.next();
            try {
                JSONObject jsonObject = new JSONObject();
                jsonObject.putAll(doc.get(MongoDocumentMapper.EVENT, new HashMap<>()));
                DomainEvent ev = (DomainEvent) jsonObject.toJavaObject(
                        Class.forName(doc.getString(MongoDocumentMapper.EVENT_TYPE)));
                EventWrapper event = new EventWrapper(
                    ev,
                    doc.getInteger(MongoDocumentMapper.VERSION),
                    doc.getDate(MongoDocumentMapper.CREATE_DATE),
                    doc.getString(MongoDocumentMapper.STREAM_ID),
                    doc.get(MongoDocumentMapper.CREATER,new HashMap<>()),
                    doc.get(MongoDocumentMapper.DESCRIPTION,new HashMap<>())
                );
                events.add(event);
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }

        }
        return events;
    }

    @Override
    public Page<EventWrapper> getStream(String modelType, String eventType, String creater, int pageSize, int pageIndex) {
        MongoCollection<Document> col = dao.getCollection(dbName, MongoDocumentMapper.EVENT_SOURCE);
        List<Bson> filter = new ArrayList<>();
        if(modelType != null && !modelType.equals(""))
            filter.add(Filters.regex(MongoDocumentMapper.STREAM_ID,"^.*"+modelType+".*$"));
        if (eventType != null && !eventType.equals(""))
            filter.add(Filters.eq(MongoDocumentMapper.EVENT_TYPE, eventType));
        if (creater != null && !creater.equals(""))
            filter.add(Filters.eq(MongoDocumentMapper.CREATER + ".id", creater));
        LinkedHashMap<String, IMongoDBDao.SortEnum> sort = new LinkedHashMap<>();
        sort.put("createDate", IMongoDBDao.SortEnum.DESC);
        Bson bson=new BasicDBObject();
        if(filter.size()>0)
            bson= Filters.and(filter);
        MongoCursor<Document> cursor = dao.findByPage(col, bson,pageIndex,pageSize,sort);

        List<EventWrapper> list = new ArrayList<>();
        while (cursor.hasNext()) {
            Document doc = cursor.next();
            try {
                JSONObject jsonObject = new JSONObject();
                jsonObject.putAll(doc.get(MongoDocumentMapper.EVENT, new HashMap<>()));
                DomainEvent ev = (DomainEvent) jsonObject.toJavaObject(
                        Class.forName(doc.getString(MongoDocumentMapper.EVENT_TYPE)));
                EventWrapper event = new EventWrapper(
                    ev,
                    doc.getInteger(MongoDocumentMapper.VERSION),
                    doc.getDate(MongoDocumentMapper.CREATE_DATE),
                    doc.getString(MongoDocumentMapper.STREAM_ID),
                    doc.get(MongoDocumentMapper.CREATER,new HashMap<>()),
                    doc.get(MongoDocumentMapper.DESCRIPTION,new HashMap<>())
                );
                list.add(event);
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }
        }
        long total = dao.count(col, bson);
        Page<EventWrapper> page = new Page<>();
        page.setPageSize(pageSize);
        page.setPageIndex(pageIndex);
        page.setTotalElements(total);
        page.setContent(list);
        return page;
    }

    @Override
    public void saveSnapshotWrapper(SnapshotWrapper eventStream,String modelCollection) {
        String collectionName = getCollectionName(modelCollection);
        ensureSnapshotIndexes(collectionName);
        MongoCollection<Document> col = dao.getCollection(dbName, collectionName);
        Map<String, Object> map = MongoDocumentMapper.snapshotFields(eventStream);

        BasicDBObject bson = new BasicDBObject();
        bson.put(MongoDocumentMapper.STREAM_ID, eventStream.getStreamId());
        if (dao.isExit(col, bson)) {
            dao.update(col, bson, map);
        } else {
            dao.addOne(col, MongoDocumentMapper.snapshot(eventStream));
        }
    }

    @Override
    public void deleteSnapshotWrapper(String streamId,String modelCollection) {
        MongoCollection<Document> col = dao.getCollection(dbName, getCollectionName(modelCollection));
        dao.delete(col, Utils.mapBuilder(MongoDocumentMapper.STREAM_ID, streamId));
    }

    @Override
    public SnapshotWrapper getSnapshotWrapper(String streamId,String modelCollection) {
        MongoCollection<Document> col = dao.getCollection(dbName, getCollectionName(modelCollection));
        BasicDBObject bson = new BasicDBObject();
        bson.put(MongoDocumentMapper.STREAM_ID, streamId);
        SnapshotWrapper snapshot = null;
        Document doc = dao.findFirst(col, bson, null);
        if (doc == null) return null;
        try {
            JSONObject jsonObject = new JSONObject(doc.get(MongoDocumentMapper.SNAPSHOT, new HashMap<>()));
            Entity en = (Entity) jsonObject.toJavaObject(Class.forName(doc.getString(MongoDocumentMapper.SNAPSHOT_TYPE)));
            snapshot = new SnapshotWrapper(doc.getString(MongoDocumentMapper.STREAM_ID), en,
                    doc.getDate(MongoDocumentMapper.CREATE_DATE));
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
        return snapshot;
    }

    @Override
    public List<SnapshotWrapper> getSnapshotWrapperList(String streamType,String modelCollection) {
        MongoCollection<Document> col = dao.getCollection(dbName, getCollectionName(modelCollection));
        BasicDBObject bson = new BasicDBObject();
        bson.put(MongoDocumentMapper.SNAPSHOT_TYPE, streamType);
        List<SnapshotWrapper> snapshotList = new ArrayList<>();
        MongoCursor<Document> cursor = dao.find(col, bson, null);
        while (cursor.hasNext()) {
            Document doc = cursor.next();
            try {
            JSONObject jsonObject = new JSONObject(doc.get(MongoDocumentMapper.SNAPSHOT, new HashMap<>()));
            Entity en = (Entity) jsonObject.toJavaObject(Class.forName(doc.getString(MongoDocumentMapper.SNAPSHOT_TYPE)));
            SnapshotWrapper snapshot = new SnapshotWrapper(doc.getString(MongoDocumentMapper.STREAM_ID), en,
                    doc.getDate(MongoDocumentMapper.CREATE_DATE));
                snapshotList.add(snapshot);
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }
        }
        return snapshotList;
    }

    @Override
    public Map getSnapshotDoc(String streamType,String modelCollection) {
        MongoCollection<Document> col = dao.getCollection(dbName, getCollectionName(modelCollection));
        BasicDBObject bson = new BasicDBObject();
        bson.put(MongoDocumentMapper.SNAPSHOT_TYPE, streamType);
        Document doc = dao.findFirst(col, bson, null);
        if (doc == null) return null;
        return doc.get(MongoDocumentMapper.SNAPSHOT, Document.class);
    }
    private String getCollectionName(String modelCollection){
        if(modelCollection!=null&&!modelCollection.isEmpty())return modelCollection;
        return MongoDocumentMapper.EVENT_SNAPSHOT;
    }

    private void ensureSnapshotIndexes(String collectionName) {
        synchronized (initializedSnapshotCollections) {
            if (initializedSnapshotCollections.contains(collectionName)) return;
            MongoIndexInitializer.initializeSnapshot(dao.getCollection(dbName, collectionName));
            initializedSnapshotCollections.add(collectionName);
        }
    }

}
