package com.sooncode.project.core.repository.mongo;

import com.alibaba.fastjson.JSONObject;
import com.mongodb.BasicDBObject;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.sooncode.project.core.finder.*;
import org.apache.commons.lang3.ArrayUtils;
import org.bson.Document;

import java.util.*;

public class FindRepository<T> implements IFindRepository<T> {

    private final Class<T> tClass;
    private final String eventSnapshot = "eventSnapshot";
    private final IMongoDBDao dao;
    private final String dbName;

    public FindRepository(Class<T> tClass) {
        this.tClass = tClass;
        this.dao = MongoSingle.getInstance().mongoDB;
        this.dbName = MongoSingle.getInstance().dbName;
    }

    @Override
    public T first(FindHelper findHelper, Sort sort) {
        MongoCollection<Document> col = dao.getCollection(dbName, eventSnapshot);
        BasicDBObject bson = FindBuild.build(tClass, findHelper, "snapshot.");
        bson.put("snapshotType", tClass.getName());
        LinkedHashMap<String, IMongoDBDao.SortEnum> sortMap = new LinkedHashMap<>();
        if (sort != null && !sort.get().isEmpty())
            sortMap = FindBuild.sort(sort, "snapshot.");
        sortMap.put("streamId", IMongoDBDao.SortEnum.ASC);
        Document doc = dao.findFirst(col, bson, sortMap);
        try {
            JSONObject jsonObject = new JSONObject();
            jsonObject.putAll(doc.get("snapshot", new HashMap<>()));
            return jsonObject.toJavaObject(tClass);
        } catch (Exception ex) {
            return null;
        }
    }

    @Override
    public List<T> list(FindHelper findHelper, Sort sort) {
        MongoCollection<Document> col = dao.getCollection(dbName, eventSnapshot);
        BasicDBObject bson = FindBuild.build(tClass, findHelper, "snapshot.");
        bson.put("snapshotType", tClass.getName());
        LinkedHashMap<String, IMongoDBDao.SortEnum> sortMap = new LinkedHashMap<>();
        if (sort != null && !sort.get().isEmpty())
            sortMap = FindBuild.sort(sort, "snapshot.");
        sortMap.put("createDate", IMongoDBDao.SortEnum.DESC);
        MongoCursor<Document> cursor = dao.find(col, bson, sortMap);
        List<T> list = new ArrayList<>();
        try {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                JSONObject jsonObject = new JSONObject();
                jsonObject.putAll(doc.get("snapshot", new HashMap<>()));
                T en = jsonObject.toJavaObject(tClass);
                list.add(en);
            }
            return list;
        } catch (Exception ex) {
            ex.printStackTrace();
            return list;
        }
    }

    @Override
    public List<T> top(FindHelper findHelper, int num, Sort sort) {
        MongoCollection<Document> col = dao.getCollection(dbName, eventSnapshot);
        BasicDBObject bson = FindBuild.build(tClass, findHelper, "snapshot.");
        bson.put("snapshotType", tClass.getName());
        LinkedHashMap<String, IMongoDBDao.SortEnum> sortMap = new LinkedHashMap<>();
        if (sort != null && !sort.get().isEmpty())
            sortMap = FindBuild.sort(sort, "snapshot.");
        sortMap.put("createDate", IMongoDBDao.SortEnum.DESC);
        MongoCursor<Document> cursor = dao.findTop(col, bson, sortMap, num);
        List<T> list = new ArrayList<>();
        try {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                JSONObject jsonObject = new JSONObject();
                jsonObject.putAll(doc.get("snapshot", new HashMap<>()));
                T en = jsonObject.toJavaObject(tClass);
                list.add(en);
            }
            return list;
        } catch (Exception ex) {
            ex.printStackTrace();
            return list;
        }
    }

    @Override
    public Page<T> page(FindHelper findHelper, int pageSize, int pageIndex, Sort sort) {
        MongoCollection<Document> col = dao.getCollection(dbName, eventSnapshot);
        BasicDBObject bson = FindBuild.build(tClass, findHelper, "snapshot.");
        bson.put("snapshotType", tClass.getName());
        Map<String, IMongoDBDao.SortEnum> sortMap = new HashMap<>();
        if (sort != null && !sort.get().isEmpty())
            sortMap = FindBuild.sort(sort, "snapshot.");
        sortMap.put("createDate", IMongoDBDao.SortEnum.DESC);
        MongoCursor<Document> cursor = dao.findByPage(col, bson, pageIndex, pageSize, sortMap);
        List<T> list = new ArrayList<>();
        try {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                JSONObject jsonObject = new JSONObject();
                jsonObject.putAll(doc.get("snapshot", new HashMap<>()));
                T en = jsonObject.toJavaObject(tClass);
                list.add(en);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
        long total = dao.count(col, bson);
        Page<T> page = new Page<>();
        page.setPageSize(pageSize);
        page.setPageIndex(pageIndex);
        page.setTotalElements(total);
        page.setContent(list);
        return page;
    }

    @Override
    public long count(FindHelper findHelper) {
        MongoCollection<Document> col = dao.getCollection(dbName, eventSnapshot);
        BasicDBObject bson = FindBuild.build(tClass, findHelper, "snapshot.");
        bson.put("snapshotType", tClass.getName());
        return dao.count(col, bson);
    }

    @Override
    public Map<String, Long> count(FindHelper findHelper, String[] groupField) {
        String statisticType="count";
        MongoCollection<Document> col = dao.getCollection(dbName, eventSnapshot);
        BasicDBObject query = FindBuild.build(tClass, findHelper, "snapshot.");
        query.put("snapshotType", tClass.getName());
        Document group = buildGroupStage(new String[]{},groupField,statisticType);
        MongoCursor<Document> cursor = dao.statistics(col, query, group, statisticType);
        Map<String, Long> map = new HashMap<>();
        try {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                String groupValue="";
                for(String g:groupField){
                    if(!groupValue.equals(""))groupValue+="_";
                    Document idDoc= (Document) doc.get("_id");
                    groupValue+=idDoc.get(g).toString();
                }
                if(groupValue.equals(""))groupValue="_";
                map.put(groupValue,doc.getLong("count"));

            }
            return map;
        } catch (Exception ex) {
            ex.printStackTrace();
            return map;
        }
    }

    @Override
    public Map<String, T> map(FindHelper findHelper, String fieldKey) {
        MongoCollection<Document> col = dao.getCollection(dbName, eventSnapshot);
        BasicDBObject bson = FindBuild.build(tClass, findHelper, "snapshot.");
        bson.put("snapshotType", tClass.getName());
        LinkedHashMap<String, IMongoDBDao.SortEnum> sortMap = new LinkedHashMap<>();
        MongoCursor<Document> cursor = dao.find(col, bson, sortMap);
        Map<String, T> map = new HashMap<>();
        try {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                JSONObject jsonObject = new JSONObject();
                jsonObject.putAll(doc.get("snapshot", new HashMap<>()));
                T en = jsonObject.toJavaObject(tClass);
                map.put(jsonObject.getString(fieldKey), en);
            }
            return map;
        } catch (Exception ex) {
            ex.printStackTrace();
            return map;
        }
    }

    @Override
    public Map<String, Object> sum(FindHelper findHelper, String[] field, String[] groupField) {
        String statisticType="sum";
        MongoCollection<Document> col = dao.getCollection(dbName, eventSnapshot);
        BasicDBObject query = FindBuild.build(tClass, findHelper, "snapshot.");
        query.put("snapshotType", tClass.getName());
        Document group = buildGroupStage(field,groupField,statisticType);
        MongoCursor<Document> cursor = dao.statistics(col, query, group, statisticType);
        Map<String, Object> map = new HashMap<>();
        try {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                String groupValue="";
                for(String g:groupField){
                    if(!groupValue.equals(""))groupValue+="_";
                    Document idDoc= (Document) doc.get("_id");
                    groupValue+=idDoc.get(g).toString();
                }
                if(groupValue.equals(""))groupValue="_";
                for(String f:field) {
                    String safeFieldName = f.replace('.', '_');
                    if(!map.containsKey(f))map.put(f,new HashMap<>());
                    Map<String,Object> fieldMap= (Map<String, Object>) map.get(f);
                    fieldMap.put(groupValue,doc.get(safeFieldName));
                }
            }
            return map;
        } catch (Exception ex) {
            ex.printStackTrace();
            return map;
        }
    }

    @Override
    public Map<String, Object> avg(FindHelper findHelper, String[] field, String[] groupField) {
        String statisticType="avg";
        MongoCollection<Document> col = dao.getCollection(dbName, eventSnapshot);
        BasicDBObject query = FindBuild.build(tClass, findHelper, "snapshot.");
        query.put("snapshotType", tClass.getName());
        Document group = buildGroupStage(field,groupField,statisticType);
        MongoCursor<Document> cursor = dao.statistics(col, query, group, statisticType);
        Map<String, Object> map = new HashMap<>();
        try {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                String groupValue="";
                for(String g:groupField){
                    if(!groupValue.equals(""))groupValue+="_";
                    Document idDoc= (Document) doc.get("_id");
                    groupValue+=idDoc.get(g).toString();
                }
                if(groupValue.equals(""))groupValue="_";
                for(String f:field) {
                    String safeFieldName = f.replace('.', '_');
                    if(!map.containsKey(f))map.put(f,new HashMap<>());
                    Map<String,Object> fieldMap= (Map<String, Object>) map.get(f);
                    fieldMap.put(groupValue,doc.get(safeFieldName));
                }
            }
            return map;
        } catch (Exception ex) {
            ex.printStackTrace();
            return map;
        }
    }

    @Override
    public Map<String, Object> max(FindHelper findHelper, String[] field, String[] groupField) {
        String statisticType="max";
        MongoCollection<Document> col = dao.getCollection(dbName, eventSnapshot);
        BasicDBObject query = FindBuild.build(tClass, findHelper, "snapshot.");
        query.put("snapshotType", tClass.getName());
        Document group = buildGroupStage(field,groupField,statisticType);
        MongoCursor<Document> cursor = dao.statistics(col, query, group, statisticType);
        Map<String, Object> map = new HashMap<>();
        try {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                String groupValue="";
                for(String g:groupField){
                    if(!groupValue.equals(""))groupValue+="_";
                    Document idDoc= (Document) doc.get("_id");
                    groupValue+=idDoc.get(g).toString();
                }
                if(groupValue.equals(""))groupValue="_";
                for(String f:field) {
                    String safeFieldName = f.replace('.', '_');
                    if(!map.containsKey(f))map.put(f,new HashMap<>());
                    Map<String,Object> fieldMap= (Map<String, Object>) map.get(f);
                    fieldMap.put(groupValue,doc.get(safeFieldName));
                }
            }
            return map;
        } catch (Exception ex) {
            ex.printStackTrace();
            return map;
        }
    }

    @Override
    public Map<String, Object> min(FindHelper findHelper, String[] field, String[] groupField) {
        String statisticType="min";
        MongoCollection<Document> col = dao.getCollection(dbName, eventSnapshot);
        BasicDBObject query = FindBuild.build(tClass, findHelper, "snapshot.");
        query.put("snapshotType", tClass.getName());
        Document group = buildGroupStage(field,groupField,statisticType);
        MongoCursor<Document> cursor = dao.statistics(col, query, group, statisticType);
        Map<String, Object> map = new HashMap<>();
        try {
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                String groupValue="";
                for(String g:groupField){
                    if(!groupValue.equals(""))groupValue+="_";
                    Document idDoc= (Document) doc.get("_id");
                    groupValue+=idDoc.get(g).toString();
                }
                if(groupValue.equals(""))groupValue="_";
                for(String f:field) {
                    String safeFieldName = f.replace('.', '_');
                    if(!map.containsKey(f))map.put(f,new HashMap<>());
                    Map<String,Object> fieldMap= (Map<String, Object>) map.get(f);
                    fieldMap.put(groupValue,doc.get(safeFieldName));
                }
            }
            return map;
        } catch (Exception ex) {
            ex.printStackTrace();
            return map;
        }
    }

    @Override
    public <TResult> List<TResult> distinct(FindHelper findHelper, String field, Class<TResult> cla) {
        MongoCollection<Document> col = dao.getCollection(dbName, eventSnapshot);
        BasicDBObject bson = FindBuild.build(tClass, findHelper, "snapshot.");
        bson.put("snapshotType", tClass.getName());
        return dao.distinct(col, bson, "snapshot."+field, cla);
    }

    /*===================*/
    private Document buildGroupStage(String[] field, String[] groupField, String statisticType) {
        Document groupFields = new Document();
        if (groupField != null && groupField.length > 0) {
            for (String gf : groupField) {
                groupFields.put(gf, "$snapshot." + gf);
            }
        }
        Document groupId = new Document("_id", groupFields);
        if ("count".equals(statisticType)) {
            // 使用 $sum: 1 进行计数
            groupId.append(statisticType, new Document("$sum", new Document("$toLong", 1)));
        } else {
            for (String f : field) {
                // 将统计结果字段名中的 . 替换为 _
                String safeFieldName = f.replace('.', '_');
                groupId.append(safeFieldName, new Document("$" + statisticType, "$snapshot." + f));
            }
        }
        return new Document("$group", groupId);
    }
}
