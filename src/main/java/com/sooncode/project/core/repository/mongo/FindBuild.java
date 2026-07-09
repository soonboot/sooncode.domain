package com.sooncode.project.core.repository.mongo;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.sooncode.project.core.finder.FindHelper;
import com.sooncode.project.core.finder.OType;
import com.sooncode.project.core.finder.Sort;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.utils.ReflectUtils;

import java.beans.PropertyDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class FindBuild {

    /**
     * 构造 BSON 过滤条件。返回的对象一定非 null：若 helper 的 and/or 列表都为空，
     * 打印 WARN（避免破坏 FindRepository 等允许外层补充条件的现有调用），但不抛异常。
     */
    public static BasicDBObject build(Class<?> tClass, FindHelper findHelper, String prefix) {
        if (tClass == null) {
            throw new DomainException("构造 BSON 过滤条件失败：tClass 不能为 null");
        }
        if (findHelper == null) {
            throw new DomainException("构造 BSON 过滤条件失败：findHelper 不能为 null");
        }
        LinkedHashMap<String, PropertyDescriptor> properties = new LinkedHashMap<>();
        for (PropertyDescriptor p : ReflectUtils.getBeanGetters(tClass)) {
            properties.put(p.getName(), p);
        }
        Set<Map.Entry<String, List<FindHelper.ValueType>>> andList = findHelper.andList();
        Set<Map.Entry<String, List<FindHelper.ValueType>>> orList = findHelper.orList();
        BasicDBObject bson = new BasicDBObject();
        BasicDBList andValues = build(andList, properties, prefix);
        BasicDBList orValues = build(orList, properties, prefix);
        if (!andValues.isEmpty()) {
            bson.put("$and", andValues);
        }
        if (!orValues.isEmpty()) {
            bson.put("$or", orValues);
        }
        return bson;
    }

    public static LinkedHashMap<String, IMongoDBDao.SortEnum> sort(Sort sort, String prefix) {
        if (sort == null) {
            return null;
        }
        LinkedHashMap<String, Sort.Type> hashSort = sort.get();
        LinkedHashMap<String, IMongoDBDao.SortEnum> hashMap = new LinkedHashMap<>();
        for (Map.Entry<String, Sort.Type> en : hashSort.entrySet()) {
            hashMap.put(prefix + en.getKey(),
                    en.getValue() == Sort.Type.asc ? IMongoDBDao.SortEnum.ASC : IMongoDBDao.SortEnum.DESC);
        }
        return hashMap;
    }

    private static BasicDBList build(Set<Map.Entry<String, List<FindHelper.ValueType>>> fields,
                                    LinkedHashMap<String, PropertyDescriptor> properties,
                                    String prefix) {
        BasicDBList values = new BasicDBList();
        if (fields == null) {
            return values;
        }
        for (Map.Entry<String, List<FindHelper.ValueType>> field : fields) {
            List<FindHelper.ValueType> vtList = field.getValue();
            if (vtList == null || vtList.isEmpty()) {
                continue;
            }
            for (FindHelper.ValueType vt : vtList) {
                values.add(buildSingleField(prefix, properties, field.getKey(), vt));
            }
        }
        return values;
    }

    /**
     * 单个 field + 单个 ValueType → 单个 BSON 片段。
     * - type == null  → 走平铺 {"key": value}（兼容老调用，eq 走该路径）
     * - type != null  → 走 {"key": {"$op": value}}（gt/lt/contains/neq 等）
     */
    private static BasicDBObject buildSingleField(String prefix,
                                                  LinkedHashMap<String, PropertyDescriptor> properties,
                                                  String fieldName,
                                                  FindHelper.ValueType vt) {
        String key = resolveKey(prefix, fieldName, properties);
        if (vt.getType() == null) {
            return new BasicDBObject(key, vt.getValue());
        }
        return new BasicDBObject(key, buildOperatorValue(vt));
    }

    private static String resolveKey(String prefix, String fieldName,
                                     LinkedHashMap<String, PropertyDescriptor> properties) {
        // dot path 取首段校验；prefix 部分跳过校验（prefix 是 Mongo 文档子文档路径，不代表 tClass 属性）
        String firstSegment = fieldName.split("\\.", 2)[0];
        if (!properties.containsKey(firstSegment)) {
            throw new DomainException("没有找到对应的字段名：" + firstSegment
                    + "，tClass=" + (properties.isEmpty() ? "?" : guessOwner(properties))
                    + "，可用字段：" + properties.keySet());
        }
        return prefix + fieldName;
    }

    private static String guessOwner(LinkedHashMap<String, PropertyDescriptor> properties) {
        // 仅用于错误信息；任意一个 property 都能反查 declaringClass
        PropertyDescriptor any = properties.values().iterator().next();
        return any.getReadMethod() == null ? "?" :
                any.getReadMethod().getDeclaringClass().getName();
    }

    private static BasicDBObject buildOperatorValue(FindHelper.ValueType vt) {
        OType type = vt.getType();
        Object value = vt.getValue();
        if (type == OType.contains) {
            if (value == null) {
                throw new DomainException("contains 查询的 value 不能为 null，type=contains");
            }
            // Pattern.quote 转义所有正则元字符，避免 1.0 误匹配 1X0、$5 误匹配行尾 等问题
            String quoted = Pattern.quote(String.valueOf(value));
            return new BasicDBObject(toMongoOp(type), "^.*" + quoted + ".*$");
        }
        return new BasicDBObject(toMongoOp(type), value);
    }

    private static String toMongoOp(OType type) {
        switch (type) {
            case eq:
                return "$eq";
            case neq:
                return "$ne";
            case gt:
                return "$gt";
            case gte:
                return "$gte";
            case lt:
                return "$lt";
            case lte:
                return "$lte";
            case in:
                return "$in";
            case nin:
                return "$nin";
            case contains:
                return "$regex";
            default:
                return "$eq";
        }
    }
}
