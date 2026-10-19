package com.sooncode.project.core.repository.pgsql;

import com.alibaba.fastjson.JSONObject;
import com.sooncode.project.core.finder.ConditionNode;
import com.sooncode.project.core.finder.FindHelper;
import com.sooncode.project.core.finder.OType;
import com.sooncode.project.core.finder.Sort;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.utils.ReflectUtils;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PGSQL 查询翻译器（对标 Mongo 的 {@code FindBuild}）。
 *
 * <p>输入是 DB 无关的 {@link FindHelper} / {@link ConditionNode} / {@link OType} / {@link Sort}，
 * 输出是 {@code WHERE + params + ORDER BY}：
 * <ul>
 *   <li>实体字段全部翻译为 JSONB 抽取表达式：单层 {@code snapshot->>'f'}，
 *       嵌套 {@code snapshot#>>'{a,b}'}；</li>
 *   <li>数值比较做防爆保护：非数值文本不会让整条查询因 {@code ::numeric} 转换失败，
 *       而是按不匹配处理（与 Mongo 行为一致）；</li>
 *   <li>字段名校验与 Mongo 侧一致：首段必须在实体 getter 中存在，否则立即抛
 *       {@link DomainException}，拼写错误在 Java 层暴露。</li>
 * </ul>
 */
public class PgFindBuild {

    /** 数值守卫的正则（PostgreSQL POSIX 风格）：整数 / 小数 / 科学计数法。 */
    private static final String NUMERIC_RE = "'^-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?$'";

    /** WHERE + 参数（不含 WHERE 关键字；无条件时 sql 为空串）。 */
    public static final class SqlFilter {
        public final String sql;
        public final List<Object> params;

        SqlFilter(String sql, List<Object> params) {
            this.sql = sql;
            this.params = params;
        }
    }

    /**
     * 翻译查询条件（不含 {@code snapshot_type} 基础条件，由调用方自行追加，
     * 以便事件/快照/回收站三种表复用）。
     */
    public static SqlFilter build(Class<?> tClass, FindHelper findHelper) {
        if (tClass == null) {
            throw new DomainException("构造 PGSQL 过滤条件失败：tClass 不能为 null");
        }
        if (findHelper == null) {
            throw new DomainException("构造 PGSQL 过滤条件失败：findHelper 不能为 null");
        }
        LinkedHashMap<String, PropertyDescriptor> properties = new LinkedHashMap<>();
        for (PropertyDescriptor p : ReflectUtils.getBeanGetters(tClass)) {
            properties.put(p.getName(), p);
        }
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        // 1) 旧 andMap/orMap 路径
        for (Map.Entry<String, List<FindHelper.ValueType>> e : findHelper.andList()) {
            for (FindHelper.ValueType vt : e.getValue()) {
                clauses.add(singleField(e.getKey(), vt, properties, params));
            }
        }
        List<String> orClauses = new ArrayList<>();
        for (Map.Entry<String, List<FindHelper.ValueType>> e : findHelper.orList()) {
            for (FindHelper.ValueType vt : e.getValue()) {
                orClauses.add(singleField(e.getKey(), vt, properties, params));
            }
        }
        if (!orClauses.isEmpty()) {
            clauses.add(orClauses.size() == 1 ? orClauses.get(0)
                    : "(" + String.join(" OR ", orClauses) + ")");
        }

        // 2) 新条件树路径
        ConditionNode root = findHelper.getRootCondition();
        if (root instanceof ConditionNode.AndNode && !((ConditionNode.AndNode) root).children.isEmpty()) {
            validateFields(root, properties);
            clauses.add(translateNode(root, properties, params));
        }
        return new SqlFilter(String.join(" AND ", clauses), params);
    }

    /** 追加 {@code ORDER BY}（用户排序在前，默认排序兜底；都为空返回空串）。 */
    public static String orderBy(Sort sort, String defaultColumn, boolean defaultDesc) {
        List<String> items = new ArrayList<>();
        if (sort != null && sort.get() != null) {
            for (Map.Entry<String, Sort.Type> e : sort.get().entrySet()) {
                items.add(jsonExpr(e.getKey()) + (e.getValue() == Sort.Type.desc ? " DESC" : " ASC"));
            }
        }
        if (defaultColumn != null && !defaultColumn.isEmpty()) {
            items.add(defaultColumn + (defaultDesc ? " DESC" : " ASC"));
        }
        return items.isEmpty() ? "" : " ORDER BY " + String.join(", ", items);
    }

    // ---------------- 条件树递归 ----------------

    static String translateNode(ConditionNode node, LinkedHashMap<String, PropertyDescriptor> properties,
                                List<Object> params) {
        if (node instanceof ConditionNode.FieldCondition) {
            ConditionNode.FieldCondition f = (ConditionNode.FieldCondition) node;
            return singleField(f.fieldName, f.valueType, properties, params);
        }
        if (node instanceof ConditionNode.AndNode) {
            List<ConditionNode> children = ((ConditionNode.AndNode) node).children;
            if (children.isEmpty()) {
                throw new DomainException("and group 不能为空");
            }
            if (children.size() == 1) {
                return translateNode(children.get(0), properties, params);
            }
            List<String> parts = new ArrayList<>(children.size());
            for (ConditionNode c : children) {
                parts.add(translateNode(c, properties, params));
            }
            return "(" + String.join(" AND ", parts) + ")";
        }
        if (node instanceof ConditionNode.OrNode) {
            List<ConditionNode> children = ((ConditionNode.OrNode) node).children;
            if (children.isEmpty()) {
                throw new DomainException("or group 不能为空");
            }
            if (children.size() == 1) {
                return translateNode(children.get(0), properties, params);
            }
            List<String> parts = new ArrayList<>(children.size());
            for (ConditionNode c : children) {
                parts.add(translateNode(c, properties, params));
            }
            return "(" + String.join(" OR ", parts) + ")";
        }
        throw new DomainException("未知的条件节点类型:" + node.getClass().getName());
    }

    // ---------------- 单字段 ----------------

    static String singleField(String fieldName,
                              FindHelper.ValueType vt,
                              LinkedHashMap<String, PropertyDescriptor> properties,
                              List<Object> params) {
        String expr = checkedExpr(fieldName, properties);
        OType type = vt == null ? null : vt.getType();
        Object value = vt == null ? null : vt.getValue();
        if (type == null) {
            // 平铺相等（兼容老调用，eq 走该路径）
            if (value == null) {
                return expr + " IS NULL";
            }
            params.add(jsonScalar(value));
            return expr + "=?";
        }
        switch (type) {
            case eq:
                if (value == null) {
                    return expr + " IS NULL";
                }
                params.add(jsonScalar(value));
                return expr + "=?";
            case neq:
                if (value == null) {
                    return expr + " IS NOT NULL";
                }
                params.add(jsonScalar(value));
                return expr + " IS DISTINCT FROM ?";
            case gt:
            case gte:
            case lt:
            case lte:
                if (value == null) {
                    throw new DomainException("数值比较的 value 不能为 null，type=" + type);
                }
                String scalar = jsonScalar(value);
                if (!isNumericLiteral(scalar)) {
                    // ?::numeric 遇到非数值文本会抛 PG 22P02，提前 fail-fast，
                    // 避免把裸驱动错误冒给调用方（Mongo 侧跨类型比较不会抛错）。
                    throw new DomainException("数值比较的 value 必须为数值类型，type=" + type
                            + "，value=" + value);
                }
                params.add(scalar);
                return numericExpr(expr) + " " + toSqlOp(type) + " ?::numeric";
            case in:
            case nin: {
                List<Object> values = toList(value);
                if (values.isEmpty()) {
                    // IN () 非法：in 空集恒为假，nin 空集恒为真
                    return type == OType.in ? "1=0" : "1=1";
                }
                // 与 Mongo $in/$nin 对齐 null 语义：$in [null] 匹配 null/缺失字段，
                // $nin [null] 排除 null/缺失字段；SQL 里 NULL 永不参与 IN/NOT IN，
                // 故把 null 拆成 IS NULL / IS NOT NULL 分支。
                boolean hasNull = false;
                List<Object> nonNulls = new ArrayList<>(values.size());
                for (Object v : values) {
                    if (v == null) {
                        hasNull = true;
                    } else {
                        nonNulls.add(v);
                    }
                }
                if (nonNulls.isEmpty()) {
                    return hasNull
                            ? (type == OType.in ? expr + " IS NULL" : expr + " IS NOT NULL")
                            : (type == OType.in ? "1=0" : "1=1");
                }
                List<String> holders = new ArrayList<>(nonNulls.size());
                for (Object v : nonNulls) {
                    holders.add("?");
                    params.add(jsonScalar(v));
                }
                if (type == OType.in) {
                    String inClause = expr + " IN (" + String.join(",", holders) + ")";
                    return hasNull ? "(" + inClause + " OR " + expr + " IS NULL)" : inClause;
                }
                String ninClause = expr + " NOT IN (" + String.join(",", holders) + ")";
                return hasNull ? "(" + ninClause + " AND " + expr + " IS NOT NULL)" : ninClause;
            }
            case contains: {
                if (value == null) {
                    throw new DomainException("contains 查询的 value 不能为 null，type=contains");
                }
                params.add("%" + PgDocumentMapper.escapeLike(String.valueOf(value)) + "%");
                return expr + " LIKE ? ESCAPE '\\'";
            }
            default:
                params.add(jsonScalar(value));
                return expr + "=?";
        }
    }

    /** JSON 标量是否为数值字面量（与 numericExpr 的 PG 正则同义的 Java 版）。 */
    static boolean isNumericLiteral(String scalar) {
        return scalar != null
                && scalar.matches("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    }

    private static String toSqlOp(OType type) {
        switch (type) {
            case gt:
                return ">";
            case gte:
                return ">=";
            case lt:
                return "<";
            case lte:
                return "<=";
            default:
                return "=";
        }
    }

    // ---------------- JSON 表达式 ----------------

    /** 实体字段 → JSONB 抽取表达式（文本形态），顺带做字段名校验。 */
    static String checkedExpr(String fieldName, LinkedHashMap<String, PropertyDescriptor> properties) {
        if (fieldName == null || fieldName.trim().isEmpty()) {
            throw new DomainException("查询字段名不能为空");
        }
        String firstSegment = fieldName.split("\\.", 2)[0];
        if (!properties.containsKey(firstSegment)) {
            throw new DomainException("没有找到对应的字段名：" + firstSegment
                    + "，可用字段：" + properties.keySet());
        }
        return jsonExpr(fieldName);
    }

    /** 实体字段 → JSONB 抽取表达式（不校验，供排序/聚合复用；调用方已保证字段合法）。 */
    public static String jsonExpr(String fieldName) {
        String[] segments = fieldName.split("\\.");
        if (segments.length == 1) {
            return "snapshot->>'" + segments[0].replace("'", "''") + "'";
        }
        StringBuilder sb = new StringBuilder("snapshot#>>'{");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(segments[i].replace("'", "''").replace("\"", ""));
        }
        sb.append("}'");
        return sb.toString();
    }

    /** 数值守卫表达式：非数值文本 → NULL（不匹配），避免整条查询因转换失败。 */
    public static String numericExpr(String textExpr) {
        return "(CASE WHEN (" + textExpr + ") ~ " + NUMERIC_RE
                + " THEN (" + textExpr + ")::numeric ELSE NULL END)";
    }

    /** 分组/排序用的分组键表达式（文本形态）。 */
    public static String groupExpr(String fieldName) {
        return jsonExpr(fieldName);
    }

    // ---------------- 值转换 ----------------

    /**
     * 查询期望值 → 与 JSONB {@code ->>} 文本可比的形态。
     * 与入库序列化（{@code JSONObject.toJSONString(entityToMap)}）同源：
     * 字符串去引号，数字/布尔取字面，Date 取毫秒（fastjson 默认行为）。
     */
    static String jsonScalar(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        String json = JSONObject.toJSONString(value);
        if (json != null && json.length() >= 2 && json.startsWith("\"") && json.endsWith("\"")) {
            return json.substring(1, json.length() - 1);
        }
        return json;
    }

    private static List<Object> toList(Object value) {
        List<Object> list = new ArrayList<>();
        if (value == null) {
            return list;
        }
        if (value instanceof Collection) {
            list.addAll((Collection<?>) value);
            return list;
        }
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            for (int i = 0; i < len; i++) {
                list.add(Array.get(value, i));
            }
            return list;
        }
        list.add(value);
        return list;
    }

    static void validateFields(ConditionNode node, LinkedHashMap<String, PropertyDescriptor> properties) {
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

    // ---------------- PG 原生 GIN 加速（加法优化，不改 build() 语义） ----------------

    /**
     * 为等值查询生成 GIN 可用的 containment 附加条件。
     *
     * <p>PG 原生用法：{@code snapshot @> '{"name":"a"}'::jsonb} 可走
     * {@code USING GIN (snapshot jsonb_path_ops)}，而现有 {@code snapshot->>'f'=?}
     * 走不上 GIN。两者 AND 叠加后语义不变（containment 是等值的必要条件），
     * 规划器能先用 GIN 快速过滤再按原表达式精确校验。
     *
     * <p>保守起见只对 {@code String} 等值 / IN 生成：数字在 JSON 里可能是 number
     * 也可能是文本，containment 的类型敏感会误杀 {@code ->>} 文本比较能命中的行；
     * 字符串两种形态一致，安全。
     *
     * @return 0..n 条 {@code snapshot @> ?::jsonb} 子句（无条件时返回空，不影响原 SQL）
     */
    public static SqlFilter ginFilters(Class<?> tClass, FindHelper findHelper) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (tClass == null || findHelper == null) {
            return new SqlFilter("", params);
        }
        LinkedHashMap<String, PropertyDescriptor> properties = new LinkedHashMap<>();
        for (PropertyDescriptor pd : ReflectUtils.getBeanGetters(tClass)) {
            properties.put(pd.getName(), pd);
        }
        // 旧 andMap 路径（类型以 ValueType 自带为准；null 视为平铺相等）
        for (Map.Entry<String, List<FindHelper.ValueType>> e : findHelper.andList()) {
            for (FindHelper.ValueType vt : e.getValue()) {
                OType t = vt == null ? null : vt.getType();
                addGinClause(e.getKey(), vt, t, properties, clauses, params);
            }
        }
        // 条件树路径（只处理顶层 AND 下的等值叶子；OR 下加 containment 会改变语义，故跳过）
        ConditionNode root = findHelper.getRootCondition();
        if (root instanceof ConditionNode.AndNode) {
            for (ConditionNode child : ((ConditionNode.AndNode) root).children) {
                if (child instanceof ConditionNode.FieldCondition) {
                    ConditionNode.FieldCondition f = (ConditionNode.FieldCondition) child;
                    OType t = f.valueType == null ? null : f.valueType.getType();
                    addGinClause(f.fieldName, f.valueType, t, properties, clauses, params);
                }
            }
        }
        return new SqlFilter(String.join(" AND ", clauses), params);
    }

    private static void addGinClause(String fieldName, FindHelper.ValueType vt, OType type,
                                     LinkedHashMap<String, PropertyDescriptor> properties,
                                     List<String> clauses, List<Object> params) {
        if (fieldName == null || fieldName.isEmpty()) {
            return;
        }
        String first = fieldName.split("\\.", 2)[0];
        if (!properties.containsKey(first)) {
            return;
        }
        Object value = vt == null ? null : vt.getValue();
        if (type == OType.in) {
            // IN 是析取语义：多值时多个 containment 必须 OR，而本方法是 AND 追加，
            // 逐值 AND 会恒为假（单行不可能同时包含两个不同值），故保守起见：
            // 单值 IN 才生成等值 containment，多值直接跳过（原 ->> IN 条件已精确）。
            List<Object> values = toList(value);
            if (values.size() == 1 && values.get(0) instanceof String) {
                clauses.add("snapshot @> ?::jsonb");
                params.add(containmentJson(fieldName, values.get(0)));
            }
            return;
        }
        // null type 视为平铺相等（与 singleField 一致），eq 同理
        if ((type == null || type == OType.eq) && value instanceof String) {
            clauses.add("snapshot @> ?::jsonb");
            params.add(containmentJson(fieldName, value));
        }
    }

    /**
     * 字段路径 + 标量 → containment JSON：{@code a.b + "x"} →
     * {@code {"a":{"b":"x"}}}。只支持字符串标量（见 ginFilters 说明）。
     */
    public static String containmentJson(String fieldName, Object value) {
        String jsonValue = JSONObject.toJSONString(value);
        String[] segments = fieldName.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = segments.length - 1; i >= 0; i--) {
            String key = segments[i].replace("\\", "\\\\").replace("\"", "");
            if (sb.length() == 0) {
                sb.append("{\"").append(key).append("\":").append(jsonValue).append("}");
            } else {
                sb = new StringBuilder("{\"").append(key).append("\":").append(sb).append("}");
            }
        }
        return sb.toString();
    }

    /** 供测试与聚合翻译复用的字段存在性校验（属性表由调用方按实体类构建）。 */
    public static Set<String> propertyNames(Class<?> tClass) {
        LinkedHashMap<String, PropertyDescriptor> properties = new LinkedHashMap<>();
        for (PropertyDescriptor p : ReflectUtils.getBeanGetters(tClass)) {
            properties.put(p.getName(), p);
        }
        return properties.keySet();
    }
}
