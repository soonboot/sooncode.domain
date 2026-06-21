package com.sooncode.project.core.repository.mongo;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.mongodb.BasicDBObject;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.model.*;
import com.sooncode.project.core.utils.Utils;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.*;

/**
 * MongoDB数据库的事件溯源存储库实体类
 */
public class MongoEventSourcingRepository implements IEventSourcingRepository {
    private IMongoDBDao dao;
    private String dbName;
    private static final String eventMetadata = "eventMetadata";
    private static final String eventSource = "eventSource";
    private static final String eventSnapshot = "eventSnapshot";
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
    }

    ;

    @Override
    public void addMetadata(EventStream stream) {
        MongoCollection<Document> col = dao.getCollection(dbName, eventMetadata);
        col.createIndex(Indexes.ascending("id"));
        Map<String, Object> map = new HashMap<>();
        map.put("id", stream.getId());
        map.put("version", stream.getVersion());
        map.put("invalid", stream.getIsInvalid());
        map.put("type", stream.getEntityType().getName());
        map.put("createDate", stream.getCreateDate());
        map.put("ver", VER);
        dao.addOne(col, map);
    }

    @Override
    public void updateMetadata(EventStream stream) {
        MongoCollection<Document> col = dao.getCollection(dbName, eventMetadata);
        Map<String, Object> map = new HashMap<>();
        map.put("version", stream.getVersion());
        map.put("invalid", stream.getIsInvalid());
        BasicDBObject bson = new BasicDBObject();
        bson.put("id", stream.getId());
        dao.update(col, bson, map);
    }

    @Override
    public void saveStream(EventWrapper stream) {
        MongoCollection<Document> col = dao.getCollection(dbName, eventSource);
        col.createIndex(Indexes.ascending("streamId"));
        Map<String, Object> map = new HashMap<>();
        map.put("id", stream.getId());
        map.put("version", stream.getEventVersion());
        map.put("streamId", stream.getEventStreamId());
        map.put("event", MongoJsonUtil.toJsonObject(stream.getEvent()));
        map.put("eventType", stream.getEventType().getName());
        map.put("creater", MongoJsonUtil.toJsonObject(stream.getCreater()));
        map.put("createDate", stream.getCreateDate());
        map.put("description", MongoJsonUtil.toJsonObject(stream.getDescription()));
        dao.addOne(col, map);
    }

    @Override
    public EventStream loadMetadata(String streamName) {
        MongoCollection<Document> col = dao.getCollection(dbName, eventMetadata);
        BasicDBObject bson = new BasicDBObject();
        bson.put("id", streamName);
        EventStream stream = null;
        MongoCursor<Document> cursor = dao.find(col, bson, null);
        while (cursor.hasNext()) {
            Document doc = cursor.next();
            try {
                stream = new EventStream(
                    doc.getString("id"),
                    doc.getInteger("version"),
                    doc.getInteger("invalid"),
                    Class.forName(doc.getString("type")),
                    doc.getDate("createDate")
                );
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }
            return stream;
        }
        return null;
    }

    @Override
    public List<EventWrapper> getStream(String streamName, Integer fromVersion, Integer toVersion) {
        MongoCollection<Document> col = dao.getCollection(dbName, eventSource);
        List<Bson> list = new ArrayList<>();
        list.add(Filters.eq("streamId", streamName));
        list.add(Filters.gte("version", fromVersion));
        list.add(Filters.lte("version", toVersion));
        LinkedHashMap<String, IMongoDBDao.SortEnum> sort = new LinkedHashMap<>();
        sort.put("version", IMongoDBDao.SortEnum.ASC);
        Bson bson = Filters.and(list);
        MongoCursor<Document> cursor = dao.find(col, bson, sort);

        List<EventWrapper> events = new ArrayList<>();
        while (cursor.hasNext()) {
            Document doc = cursor.next();
            try {
                JSONObject jsonObject = new JSONObject();
                jsonObject.putAll(doc.get("event", new HashMap<>()));
                DomainEvent ev = (DomainEvent) jsonObject.toJavaObject(Class.forName(doc.getString("eventType")));
                EventWrapper event = new EventWrapper(
                    ev,
                    doc.getInteger("version"),
                    doc.getDate("createDate"),
                    doc.getString("streamId"),
                    doc.get("creater",new HashMap<>()),
                    doc.get("description",new HashMap<>())
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
        MongoCollection<Document> col = dao.getCollection(dbName, eventSource);
        List<Bson> filter = new ArrayList<>();
        if(modelType != null && !modelType.equals(""))
            filter.add(Filters.regex("streamId","^.*"+modelType+".*$"));
        if (eventType != null && !eventType.equals(""))
            filter.add(Filters.eq("eventType", eventType));
        if (creater != null && !creater.equals(""))
            filter.add(Filters.eq("creater.id", creater));
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
                jsonObject.putAll(doc.get("event", new HashMap<>()));
                DomainEvent ev = (DomainEvent) jsonObject.toJavaObject(Class.forName(doc.getString("eventType")));
                EventWrapper event = new EventWrapper(
                    ev,
                    doc.getInteger("version"),
                    doc.getDate("createDate"),
                    doc.getString("streamId"),
                    doc.get("creater",new HashMap<>()),
                    doc.get("description",new HashMap<>())
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
        MongoCollection<Document> col = dao.getCollection(dbName, getCollectionName(modelCollection));
        Map<String, Object> map = new HashMap<>();
        map.put("snapshot", MongoJsonUtil.toJsonObject(eventStream.getSnapshot()));
        map.put("createDate", eventStream.getCreateDate());

        BasicDBObject bson = new BasicDBObject();
        bson.put("streamId", eventStream.getStreamId());
        if (dao.isExit(col, bson)) {
            dao.update(col, bson, map);
        } else {
            col.createIndex(Indexes.ascending("streamId"));
            col.createIndex(Indexes.ascending("snapshotType"));
            col.createIndex(Indexes.descending("createDate"));
            map.put("snapshotType", eventStream.getSnapshotType().getName());
            map.put("streamId", eventStream.getStreamId());
            dao.addOne(col, map);
        }
    }

    @Override
    public void deleteSnapshotWrapper(String streamId,String modelCollection) {
        MongoCollection<Document> col = dao.getCollection(dbName, getCollectionName(modelCollection));
        dao.delete(col, Utils.mapBuilder("streamId", streamId));
    }

    @Override
    public SnapshotWrapper getSnapshotWrapper(String streamId,String modelCollection) {
        MongoCollection<Document> col = dao.getCollection(dbName, getCollectionName(modelCollection));
        BasicDBObject bson = new BasicDBObject();
        bson.put("streamId", streamId);
        SnapshotWrapper snapshot = null;
        Document doc = dao.findFirst(col, bson, null);
        if (doc == null) return null;
        try {
            JSONObject jsonObject = new JSONObject(doc.get("snapshot", new HashMap<>()));
            Entity en = (Entity) jsonObject.toJavaObject(Class.forName(doc.getString("snapshotType")));
            snapshot = new SnapshotWrapper(doc.getString("streamId"), en);
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
        bson.put("snapshotType", streamType);
        List<SnapshotWrapper> snapshotList = new ArrayList<>();
        MongoCursor<Document> cursor = dao.find(col, bson, null);
        while (cursor.hasNext()) {
            Document doc = cursor.next();
            try {
                JSONObject jsonObject = new JSONObject(doc.get("snapshot", new HashMap<>()));
                Entity en = (Entity) jsonObject.toJavaObject(Class.forName(doc.getString("eventType")));
                SnapshotWrapper snapshot = new SnapshotWrapper(doc.getString("streamId"), en);
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
        bson.put("snapshotType", streamType);
        Document doc = dao.findFirst(col, bson, null);
        if (doc == null) return null;
        return doc.get("snapshot", Document.class);
    }
    private String getCollectionName(String modelCollection){
        if(modelCollection!=null&&!modelCollection.isEmpty())return modelCollection;
        return eventSnapshot;
    }

}
