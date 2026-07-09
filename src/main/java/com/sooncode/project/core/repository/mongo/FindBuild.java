package com.sooncode.project.core.repository.mongo;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.sooncode.project.core.finder.ConditionNode;
import com.sooncode.project.core.finder.FindHelper;
import com.sooncode.project.core.finder.OType;
import com.sooncode.project.core.finder.Sort;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.utils.ReflectUtils;

import java.beans.PropertyDescriptor;
import java.util.ArrayList;
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
        BasicDBObject bson = new BasicDBObject();

        // 1) 先翻译旧 andMap/orMap 路径（byField/or 旧 API）——先 put 进 bson
        Set<Map.Entry<String, List<FindHelper.ValueType>>> andList = findHelper.andList();
        Set<Map.Entry<String, List<FindHelper.ValueType>>> orList = findHelper.orList();
        boolean oldPathUsed = false;
        BasicDBList andValues = build(andList, properties, prefix);
        BasicDBList orValues = build(orList, properties, prefix);
        if (!andValues.isEmpty()) {
            bson.put("$and", andValues);
            oldPathUsed = true;
        }
        if (!orValues.isEmpty()) {
            bson.put("$or", orValues);
            oldPathUsed = true;
        }

        // 2) 再翻译新条件树（andGroup/orGroup 路径）——用 mergeInto 合并到已有 bson
        ConditionNode rootCondition = findHelper.getRootCondition();
        if (rootCondition != null && rootCondition instanceof ConditionNode.AndNode) {
            ConditionNode.AndNode root = (ConditionNode.AndNode) rootCondition;
            if (!root.children.isEmpty()) {
                validateFields(root, properties);
                BasicDBObject treeBson = root.toBson(prefix);
                if (treeBson != null && !treeBson.isEmpty()) {
                    mergeInto(bson, treeBson);
                }
            }
        }
        if (!oldPathUsed && (andValues.isEmpty() && orValues.isEmpty())
                && (rootCondition == null || ((ConditionNode.AndNode) rootCondition).children.isEmpty())) {
            System.err.println("[FindBuild WARN] findHelper 的 and/or 列表都为空，"
                    + "将产生空 BSON 条件（Mongo 会匹配全部文档，注意全表扫描风险）。tClass="
                    + tClass.getName() + "，prefix=" + prefix);
        }
        return bson;
    }

    /**
     * 把子 BSON 合并进父 BSON。
     * <ul>
     *   <li>子 BSON 形如 {field: value} → 直接 put</li>
     *   <li>子 BSON 形如 {$and: [...]} / {$or: [...]} → 合并数组</li>
     *   <li>如果父 BSON 已经有同名 key（如 $and），把子数组元素 append 到父数组（去重）</li>
     * </ul>
     */
    private static void mergeInto(BasicDBObject parent, BasicDBObject child) {
        for (Map.Entry<String, Object> e : child.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (parent.containsKey(key) && (key.equals("$and") || key.equals("$or"))) {
                // 父已有同操作符 → 把子数组元素 merge 进去
                List<Object> existing = new ArrayList<>((List<Object>) parent.get(key));
                List<Object> incoming = (List<Object>) value;
                for (Object inc : incoming) {
                    if (!existing.contains(inc)) {
                        existing.add(inc);
                    }
                }
                parent.put(key, existing);
            } else {
                parent.put(key, value);
            }
        }
    }

    /**
     * {@link ConditionNode.FieldCondition} 的翻译入口。
     * 暴露为 static 供 ConditionNode 调用，复用本类的 contains 转义等基础设施。
     * 注意：字段名校验由外层的 {@link #validateFields} 保证，此方法不做二次校验。
     */
    public static BasicDBObject translateField(String prefix, String fieldName, FindHelper.ValueType vt) {
        if (vt.getType() == null) {
            return new BasicDBObject(prefix + fieldName, vt.getValue());
        }
        OType type = vt.getType();
        Object value = vt.getValue();
        if (type == OType.contains) {
            if (value == null) {
                throw new DomainException("contains 查询的 value 不能为 null，type=contains");
            }
            String quoted = Pattern.quote(String.valueOf(value));
            return new BasicDBObject(prefix + fieldName, new BasicDBObject(toMongoOp(type), "^.*" + quoted + ".*$"));
        }
        return new BasicDBObject(prefix + fieldName, new BasicDBObject(toMongoOp(type), value));
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

    /**
     * 把 rootCondition 翻译为 BSON（仅 ConditionNode 翻译路径，不混合旧 andMap/orMap）。
     * 主要由测试和 ConditionNode 自调用使用。
     */
    public static BasicDBObject translateRoot(ConditionNode root, String prefix) {
        if (root == null) {
            return new BasicDBObject();
        }
        return root.toBson(prefix);
    }

    /**
     * 深度遍历 condition 树，校验所有 FieldCondition 的字段名在 properties 中存在。
     * 与旧路径 {@link #resolveKey} 的校验行为一致，确保拼写错误在 Java 层立即暴露。
     */
    private static void validateFields(ConditionNode node,
                                        LinkedHashMap<String, PropertyDescriptor> properties) {
        if (node instanceof ConditionNode.FieldCondition) {
            String fieldName = ((ConditionNode.FieldCondition) node).fieldName;
            String firstSegment = fieldName.split("\\.", 2)[0];
            if (!properties.containsKey(firstSegment)) {
                throw new DomainException("没有找到对应的字段名：" + firstSegment
                        + "，可用字段：" + properties.keySet());
            }
        } else if (node instanceof ConditionNode.AndNode) {
            for (ConditionNode child : ((ConditionNode.AndNode) node).children) {
                validateFields(child, properties);
            }
        } else if (node instanceof ConditionNode.OrNode) {
            for (ConditionNode child : ((ConditionNode.OrNode) node).children) {
                validateFields(child, properties);
            }
        }
    }
}
