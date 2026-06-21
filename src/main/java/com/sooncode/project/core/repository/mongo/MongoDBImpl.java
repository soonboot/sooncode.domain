package com.sooncode.project.core.repository.mongo;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.DeleteResult;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.*;

public class MongoDBImpl implements IMongoDBDao{

    private MongoDBUtil dbUtil;
    public MongoDBImpl(String host,int port){
        this.dbUtil=new MongoDBUtil(host,port);
    }
    public MongoDBImpl(String host,int port,String user,String password){
        this.dbUtil=new MongoDBUtil(host,port,user,password);
    }
    @Override
    public MongoDatabase getDb(String dbName) {
        if (dbName != null && !"".equals(dbName)) {
            MongoDatabase database = dbUtil.getDatabase(dbName);
            return database;
        }
        return null;
    }

    @Override
    public MongoCollection<Document> getCollection(String dbName, String collectionName) {
        if (null == collectionName || "".equals(collectionName)) {
            return null;
        }
        if (null == dbName || "".equals(dbName)) {
            return null;
        }
        MongoCollection<Document> collection = dbUtil.getDatabase(dbName).getCollection(collectionName);
        return collection;
    }

    @Override
    public boolean addOne(MongoCollection<Document> collection, Map<String, Object> map) {
        Document document=new Document(map);
        collection.insertOne(document);
        return true;
    }

    @Override
    public boolean addMany(MongoCollection<Document> collection, List<Map<String,Object>> list){
        List<Document> docList=new ArrayList<>();
        for(Map map:list){
            docList.add(new Document(map));
        }
        collection.insertMany(docList);
        return true;
    }

    @Override
    public int delete(MongoCollection<Document> collection, Map<String, Object> filter) {
        BasicDBObject bson=new BasicDBObject();
        for(Map.Entry<String,Object> en:filter.entrySet()){
            bson.put(en.getKey(),en.getValue());
        }
        DeleteResult deleteResult=collection.deleteMany(bson);
        return (int)deleteResult.getDeletedCount();
    }

    @Override
    public int deleteById(MongoCollection<Document> coll, String id) {
        int count = 0;
        ObjectId _id = null;
        try {
                _id = new ObjectId(id);
            } catch (Exception e) {
                return 0;
            }
        Bson filter = Filters.eq("_id", _id);
        DeleteResult deleteResult = coll.deleteOne(filter);
        count = (int) deleteResult.getDeletedCount();
        return count;
    }

    @Override
    public Document findFirst(MongoCollection<Document> coll, Bson filter, LinkedHashMap<String, SortEnum> sort) {
        if(sort==null)
            return coll.find(filter).first();
        else{
            BasicDBObject orderBy=new BasicDBObject();
            for(Map.Entry<String,SortEnum> m:sort.entrySet()){
                orderBy.put(m.getKey(),m.getValue().value());
            }
            return coll.find(filter).sort(orderBy).first();
        }
    }

    @Override
    public long count(MongoCollection<Document> coll, Bson filter) {
        return coll.countDocuments(filter);
    }

    @Override
    public MongoCursor<Document> findTop(MongoCollection<Document> coll, Bson filter, LinkedHashMap<String, SortEnum> sort,int num) {
        if(sort==null)
            return coll.find(filter).limit(num).iterator();
        else{
            BasicDBObject orderBy=new BasicDBObject();
            for(Map.Entry<String,SortEnum> m:sort.entrySet()){
                orderBy.put(m.getKey(),m.getValue().value());
            }
            return coll.find(filter).sort(orderBy).limit(num).iterator();
        }
    }

    @Override
    public MongoCursor<Document> find(MongoCollection<Document> coll, Bson filter,LinkedHashMap<String,SortEnum> sort) {
        if(sort==null)
            return coll.find(filter).iterator();
        else{
            BasicDBObject orderBy=new BasicDBObject();
            for(Map.Entry<String,SortEnum> m:sort.entrySet()){
                orderBy.put(m.getKey(),m.getValue().value());
            }
            return coll.find(filter).sort(orderBy).iterator();
        }
    }

    @Override
    public MongoCursor<Document> findByPage(MongoCollection<Document> coll, Bson filter, int pageIndex, int pageSize,Map<String,SortEnum> sort) {
        if(sort==null)
            return coll.find(filter).skip(pageIndex * pageSize).limit(pageSize).iterator();
        BasicDBObject orderBy = new BasicDBObject();
        for(Map.Entry<String,SortEnum> e:sort.entrySet()) {
            orderBy.put(e.getKey(), e.getValue().value());
        }
        return coll.find(filter).sort(orderBy).skip(pageIndex * pageSize).limit(pageSize).iterator();
    }

    @Override
    public Document findById(MongoCollection<Document> coll, String id) {
        ObjectId _idobj = null;
        try {
            _idobj = new ObjectId(id);
        } catch (Exception e) {
            return null;
        }
        Document myDoc = coll.find(Filters.eq("_id", _idobj)).first();
        return myDoc;
    }

    @Override
    public void update(MongoCollection<Document> coll,Map<String,Object> filter,Map<String,Object> newData){
        BasicDBObject bson=new BasicDBObject();
        for(Map.Entry<String,Object> en:filter.entrySet()){
            bson.put(en.getKey(),en.getValue());
        }
        Document doc=new Document();

        for(Map.Entry<String,Object> en:newData.entrySet()){
            doc.append("$set",new Document(newData));
        }
        coll.updateOne(bson,doc);
    }

    @Override
    public void updateById(MongoCollection<Document> coll, String id, Map<String,Object> newData) {
        ObjectId _idobj = null;
        try {
                _idobj = new ObjectId(id);
            } catch (Exception e) {
            }
        Bson filter = Filters.eq("_id", _idobj);

        coll.updateOne(filter, new Document("$set", new Document(newData)));
    }

    @Override
    public void dropCollection(String dbName, String collName) {
        getCollection(dbName,collName).drop();
    }

    @Override
    public boolean isExit(MongoCollection<Document> collection,Bson filter) {
         if(collection.countDocuments(filter)>0){
             return true;
         }
         else return false;
    }

    @Override
    public  <TResult> List<TResult>  distinct(MongoCollection<Document> collection, Map<String,Object> filter, String field, Class<TResult> clazz) {
        BasicDBObject bson=new BasicDBObject();
        for(Map.Entry<String,Object> en:filter.entrySet()){
            bson.put(en.getKey(),en.getValue());
        }
        List<TResult> result= collection.distinct(field,bson,clazz).into(new ArrayList<TResult>());
        return result;
    }
    @Override
    public MongoCursor<Document> statistics(MongoCollection<Document> coll, Map<String,Object> filter,Document groupField,String statisticType){
        BasicDBObject query=new BasicDBObject();
        for(Map.Entry<String,Object> en:filter.entrySet()){
            query.put(en.getKey(),en.getValue());
        }
        Document match = new Document("$match", query);

        List<Document> pipline = new ArrayList<>();
        pipline.add(match);
        pipline.add(groupField);
        return coll.aggregate(pipline).iterator();
    }
}
