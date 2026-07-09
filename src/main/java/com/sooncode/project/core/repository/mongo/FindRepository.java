package com.sooncode.project.core.repository.mongo;

import com.alibaba.fastjson.JSONObject;
import com.mongodb.BasicDBObject;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.sooncode.project.core.finder.FindHelper;
import com.sooncode.project.core.finder.IFindRepository;
import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.finder.Sort;
import com.sooncode.project.core.model.DomainException;
import org.bson.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MongoDB 事件快照仓库。
 * <p>
 * 文档结构：{@code { _id, streamId, snapshotType, snapshot: {实体字段...} }}
 * <p>
 * 关键约定：
 * <ul>
 *   <li>所有 MongoCursor 通过 try-with-resources 自动关闭，避免连接池泄漏；</li>
 *   <li>所有反序列化（Document → JSONObject → T）走 {@link #deserializeSnapshot} 单一入口；</li>
 *   <li>异常透传由 DomainException 统一包装，调用方 / Spring 全局处理器接管。</li>
 * </ul>
 *
 * @param <T> 实体类型
 */
public class FindRepository<T> implements IFindRepository<T> {

    private static final String SNAPSHOT_FIELD = "snapshot";
    private static final String SNAPSHOT_TYPE_FIELD = "snapshotType";
    private static final String SNAPSHOT_PREFIX = "snapshot.";

    private final Class<T> tClass;
    private final IMongoDBDao dao;
    private final String dbName;
    private final String snapshotCollection;

    public FindRepository(Class<T> tClass) {
        this(tClass, "eventSnapshot");
    }

    public FindRepository(Class<T> tClass, String snapshotCollection) {
        if (tClass == null) {
            throw new DomainException("FindRepository 构造失败：tClass 不能为 null");
        }
        this.tClass = tClass;
        MongoSingle single = MongoSingle.getInstance();
        if (single == null || single.mongoDB == null) {
            throw new DomainException("FindRepository 构造失败：MongoSingle 未初始化，"
                    + "请先调用 new MongoConnection(...) 完成 MongoDB 配置注入");
        }
        if (single.dbName == null || single.dbName.isEmpty()) {
            throw new DomainException("FindRepository 构造失败：MongoSingle.dbName 未设置");
        }
        this.dao = single.mongoDB;
        this.dbName = single.dbName;
        this.snapshotCollection = snapshotCollection;
    }

    @Override
    public T first(FindHelper findHelper, Sort sort) {
        MongoCollection<Document> col = dao.getCollection(dbName, snapshotCollection);
        BasicDBObject bson = buildFilter(findHelper);
        LinkedHashMap<String, IMongoDBDao.SortEnum> sortMap = buildSortMap(sort, "streamId", IMongoDBDao.SortEnum.ASC);
        Document doc = dao.findFirst(col, bson, sortMap);
        if (doc == null) {
            return null;
        }
        return deserializeSnapshot(doc);
    }

    @Override
    public List<T> list(FindHelper findHelper, Sort sort) {
        MongoCollection<Document> col = dao.getCollection(dbName, snapshotCollection);
        BasicDBObject bson = buildFilter(findHelper);
        LinkedHashMap<String, IMongoDBDao.SortEnum> sortMap = buildSortMap(sort, "createDate", IMongoDBDao.SortEnum.DESC);
        try (MongoCursor<Document> cursor = dao.find(col, bson, sortMap)) {
            List<T> list = new ArrayList<>();
            while (cursor.hasNext()) {
                list.add(deserializeSnapshot(cursor.next()));
            }
            return list;
        }
    }

    @Override
    public List<T> top(FindHelper findHelper, int num, Sort sort) {
        MongoCollection<Document> col = dao.getCollection(dbName, snapshotCollection);
        BasicDBObject bson = buildFilter(findHelper);
        LinkedHashMap<String, IMongoDBDao.SortEnum> sortMap = buildSortMap(sort, "createDate", IMongoDBDao.SortEnum.DESC);
        try (MongoCursor<Document> cursor = dao.findTop(col, bson, sortMap, num)) {
            List<T> list = new ArrayList<>();
            while (cursor.hasNext()) {
                list.add(deserializeSnapshot(cursor.next()));
            }
            return list;
        }
    }

    @Override
    public Page<T> page(FindHelper findHelper, int pageSize, int pageIndex, Sort sort) {
        MongoCollection<Document> col = dao.getCollection(dbName, snapshotCollection);
        BasicDBObject bson = buildFilter(findHelper);
        LinkedHashMap<String, IMongoDBDao.SortEnum> sortMap = buildSortMap(sort, "createDate", IMongoDBDao.SortEnum.DESC);
        List<T> list;
        try (MongoCursor<Document> cursor = dao.findByPage(col, bson, pageIndex, pageSize, sortMap)) {
            list = new ArrayList<>();
            while (cursor.hasNext()) {
                list.add(deserializeSnapshot(cursor.next()));
            }
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
        MongoCollection<Document> col = dao.getCollection(dbName, snapshotCollection);
        BasicDBObject bson = buildFilter(findHelper);
        return dao.count(col, bson);
    }

    @Override
    public Map<String, Long> count(FindHelper findHelper, String[] groupField) {
        validateGroupField(groupField);
        MongoCollection<Document> col = dao.getCollection(dbName, snapshotCollection);
        BasicDBObject query = buildFilter(findHelper);
        Document group = buildGroupStage(new String[]{}, groupField, "count");
        try (MongoCursor<Document> cursor = dao.statistics(col, query, group, "count")) {
            Map<String, Long> map = new HashMap<>();
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                map.put(extractGroupValue(doc, groupField), doc.getLong("count"));
            }
            return map;
        }
    }

    @Override
    public Map<String, T> map(FindHelper findHelper, String fieldKey) {
        if (fieldKey == null || fieldKey.isEmpty()) {
            throw new DomainException("FindRepository.map 失败：fieldKey 不能为空");
        }
        MongoCollection<Document> col = dao.getCollection(dbName, snapshotCollection);
        BasicDBObject bson = buildFilter(findHelper);
        try (MongoCursor<Document> cursor = dao.find(col, bson, new LinkedHashMap<>())) {
            Map<String, T> map = new HashMap<>();
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                JSONObject json = new JSONObject();
                json.putAll(doc.get(SNAPSHOT_FIELD, new HashMap<>()));
                String key = json.getString(fieldKey);
                map.put(key == null ? "" : key, json.toJavaObject(tClass));
            }
            return map;
        }
    }

    @Override
    public Map<String, Object> sum(FindHelper findHelper, String[] field, String[] groupField) {
        return aggregate(findHelper, field, groupField, "sum");
    }

    @Override
    public Map<String, Object> avg(FindHelper findHelper, String[] field, String[] groupField) {
        return aggregate(findHelper, field, groupField, "avg");
    }

    @Override
    public Map<String, Object> max(FindHelper findHelper, String[] field, String[] groupField) {
        return aggregate(findHelper, field, groupField, "max");
    }

    @Override
    public Map<String, Object> min(FindHelper findHelper, String[] field, String[] groupField) {
        return aggregate(findHelper, field, groupField, "min");
    }

    @Override
    public <TResult> List<TResult> distinct(FindHelper findHelper, String field, Class<TResult> cla) {
        if (field == null || field.isEmpty()) {
            throw new DomainException("FindRepository.distinct 失败：field 不能为空");
        }
        MongoCollection<Document> col = dao.getCollection(dbName, snapshotCollection);
        BasicDBObject bson = buildFilter(findHelper);
        return dao.distinct(col, bson, SNAPSHOT_PREFIX + field, cla);
    }

    /* =================== 内部工具方法 =================== */

    /**
     * 构造 BSON 过滤条件。基础条件：
     * <ul>
     *   <li>snapshotType = tClass.getName()（区分不同聚合根的事件快照）</li>
     *   <li>findHelper 提供的 and/or 条件</li>
     * </ul>
     */
    private BasicDBObject buildFilter(FindHelper findHelper) {
        BasicDBObject bson = FindBuild.build(tClass, findHelper, SNAPSHOT_PREFIX);
        bson.put(SNAPSHOT_TYPE_FIELD, tClass.getName());
        return bson;
    }

    /**
     * 构造排序映射：合并用户传入 sort 与默认 sort（defaultField/defaultOrder）。
     */
    private LinkedHashMap<String, IMongoDBDao.SortEnum> buildSortMap(Sort sort, String defaultField, IMongoDBDao.SortEnum defaultOrder) {
        LinkedHashMap<String, IMongoDBDao.SortEnum> sortMap = new LinkedHashMap<>();
        if (sort != null && !sort.get().isEmpty()) {
            sortMap.putAll(FindBuild.sort(sort, SNAPSHOT_PREFIX));
        }
        sortMap.put(defaultField, defaultOrder);
        return sortMap;
    }

    /**
     * Document → T。从 snapshot 子文档反序列化为目标实体类型。
     */
    private T deserializeSnapshot(Document doc) {
        Object snapshot = doc.get(SNAPSHOT_FIELD);
        JSONObject jsonObject = new JSONObject();
        if (snapshot instanceof Map) {
            jsonObject.putAll((Map<String, Object>) snapshot);
        }
        return jsonObject.toJavaObject(tClass);
    }

    /**
     * 统一聚合入口（sum/avg/max/min）。statisticType 决定 Mongo 操作符。
     */
    private Map<String, Object> aggregate(FindHelper findHelper, String[] field, String[] groupField, String statisticType) {
        if (field == null || field.length == 0) {
            throw new DomainException("FindRepository." + statisticType + " 失败：统计字段 field 不能为空");
        }
        validateGroupField(groupField);
        MongoCollection<Document> col = dao.getCollection(dbName, snapshotCollection);
        BasicDBObject query = buildFilter(findHelper);
        Document group = buildGroupStage(field, groupField, statisticType);
        try (MongoCursor<Document> cursor = dao.statistics(col, query, group, statisticType)) {
            Map<String, Object> map = new HashMap<>();
            while (cursor.hasNext()) {
                Document doc = cursor.next();
                String groupValue = extractGroupValue(doc, groupField);
                for (String f : field) {
                    String safeFieldName = f.replace('.', '_');
                    Map<String, Object> fieldMap = (Map<String, Object>) map.computeIfAbsent(f, k -> new HashMap<>());
                    fieldMap.put(groupValue, doc.get(safeFieldName));
                }
            }
            return map;
        }
    }

    private void validateGroupField(String[] groupField) {
        if (groupField == null || groupField.length == 0) {
            throw new DomainException("groupField 不能为空：聚合分组必须指定至少一个分组字段，"
                    + "如果确实不需要分组请使用 count(findHelper) 重载");
        }
    }

    /**
     * 从 Mongo 聚合 _id 子文档拼接 groupValue。null 字段安全（用 _ 占位）。
     */
    private String extractGroupValue(Document doc, String[] groupField) {
        Document idDoc = (Document) doc.get("_id");
        if (idDoc == null) {
            return "_";
        }
        StringBuilder sb = new StringBuilder();
        for (String g : groupField) {
            if (sb.length() > 0) {
                sb.append("_");
            }
            sb.append(String.valueOf(idDoc.get(g)));
        }
        if (sb.length() == 0) {
            return "_";
        }
        return sb.toString();
    }

    private Document buildGroupStage(String[] field, String[] groupField, String statisticType) {
        Document groupFields = new Document();
        if (groupField != null) {
            for (String gf : groupField) {
                groupFields.put(gf, "$" + SNAPSHOT_FIELD + "." + gf);
            }
        }
        Document groupId = new Document("_id", groupFields);
        if ("count".equals(statisticType)) {
            groupId.append(statisticType, new Document("$sum", new Document("$toLong", 1)));
        } else {
            for (String f : field) {
                String safeFieldName = f.replace('.', '_');
                groupId.append(safeFieldName, new Document("$" + statisticType, "$" + SNAPSHOT_FIELD + "." + f));
            }
        }
        return new Document("$group", groupId);
    }
}
