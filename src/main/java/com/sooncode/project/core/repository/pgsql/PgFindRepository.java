package com.sooncode.project.core.repository.pgsql;

import com.alibaba.fastjson.JSONObject;
import com.sooncode.project.core.finder.FindHelper;
import com.sooncode.project.core.finder.IFindRepository;
import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.finder.Sort;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.utils.BaseTypeConvert;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PGSQL 快照查询仓储（文档形态，对标 {@code mongo.FindRepository}）。
 *
 * <p>行结构 {@code {stream_id, snapshot_type, snapshot(JSONB), create_date}}，
 * 查询语义与 Mongo 侧逐项对齐：
 * <ul>
 *   <li>默认按 {@code snapshot_type = 实体类全名} 隔离不同聚合根；</li>
 *   <li>默认排序：{@code first → stream_id ASC}，其余 → {@code create_date DESC}；</li>
 *   <li>分页 {@code pageIndex} 从 0 开始（{@code OFFSET = pageIndex * pageSize}）；</li>
 *   <li>聚合分组键用 {@code _} 连接，null 记为 {@code "null"}（与 Mongo 的
 *       {@code String.valueOf} 拼接一致）。</li>
 * </ul>
 *
 * @param <T> 实体类型
 */
public class PgFindRepository<T extends DomainModel<T>> implements IFindRepository<T> {

    private final Class<T> tClass;
    private final PgDao dao;
    private final String snapshotTable;

    public PgFindRepository(Class<T> tClass, PgDao dao) {
        this(tClass, dao, PgDocumentMapper.snapshotTable(tClass));
    }

    public PgFindRepository(Class<T> tClass, PgDao dao, String snapshotTable) {
        if (tClass == null) {
            throw new DomainException("PgFindRepository 构造失败：tClass 不能为 null");
        }
        if (dao == null) {
            throw new DomainException("PgFindRepository 构造失败：dao 不能为空");
        }
        this.tClass = tClass;
        this.dao = dao;
        this.snapshotTable = PgDocumentMapper.snapshotTable(snapshotTable);
    }

    @Override
    public T first(FindHelper findHelper, Sort sort) {
        Bound bound = select(findHelper, "stream_id, snapshot_type, snapshot, create_date",
                PgFindBuild.orderBy(sort, "stream_id", false) + " LIMIT 1");
        Map<String, Object> row = dao.queryOne(bound.sql, bound.params);
        return row == null ? null : deserialize(row);
    }

    @Override
    public List<T> list(FindHelper findHelper, Sort sort) {
        Bound bound = select(findHelper, "stream_id, snapshot_type, snapshot, create_date",
                PgFindBuild.orderBy(sort, "create_date", true));
        List<T> list = new ArrayList<>();
        for (Map<String, Object> row : dao.queryList(bound.sql, bound.params)) {
            list.add(deserialize(row));
        }
        return list;
    }

    @Override
    public List<T> top(FindHelper findHelper, int num, Sort sort) {
        Bound bound = select(findHelper, "stream_id, snapshot_type, snapshot, create_date",
                PgFindBuild.orderBy(sort, "create_date", true) + " LIMIT ?");
        bound.params.add(num);
        List<T> list = new ArrayList<>();
        for (Map<String, Object> row : dao.queryList(bound.sql, bound.params)) {
            list.add(deserialize(row));
        }
        return list;
    }

    @Override
    public Page<T> page(FindHelper findHelper, int pageSize, int pageIndex, Sort sort) {
        Bound bound = select(findHelper, "stream_id, snapshot_type, snapshot, create_date",
                PgFindBuild.orderBy(sort, "create_date", true) + " LIMIT ? OFFSET ?");
        bound.params.add(pageSize);
        bound.params.add((long) pageIndex * pageSize);
        List<T> list = new ArrayList<>();
        for (Map<String, Object> row : dao.queryList(bound.sql, bound.params)) {
            list.add(deserialize(row));
        }
        long total = count(findHelper);
        Page<T> page = new Page<>();
        page.setPageSize(pageSize);
        page.setPageIndex(pageIndex);
        page.setTotalElements(total);
        page.setContent(list);
        return page;
    }

    @Override
    public long count(FindHelper findHelper) {
        Bound bound = select(findHelper, "COUNT(*) AS c", "");
        return dao.queryLong(bound.sql, bound.params);
    }

    @Override
    public Map<String, Long> count(FindHelper findHelper, String[] groupField) {
        validateGroupField(groupField);
        StringBuilder select = new StringBuilder();
        for (int i = 0; i < groupField.length; i++) {
            if (i > 0) {
                select.append(",");
            }
            select.append(PgFindBuild.groupExpr(groupField[i])).append(" AS g").append(i);
        }
        select.append(",COUNT(*) AS c");
        Bound bound = select(findHelper, select.toString(), groupClause(groupField.length));
        Map<String, Long> map = new HashMap<>();
        for (Map<String, Object> row : dao.queryList(bound.sql, bound.params)) {
            map.put(groupValue(row, groupField.length),
                    ((Number) row.get("c")).longValue());
        }
        return map;
    }

    @Override
    public Map<String, T> map(FindHelper findHelper, String fieldKey) {
        if (fieldKey == null || fieldKey.isEmpty()) {
            throw new DomainException("PgFindRepository.map 失败：fieldKey 不能为空");
        }
        Bound bound = select(findHelper, "snapshot", "");
        Map<String, T> map = new HashMap<>();
        for (Map<String, Object> row : dao.queryList(bound.sql, bound.params)) {
            JSONObject json = PgDocumentMapper.parseObject(PgDao.str(row.get("snapshot")));
            if (json == null) {
                json = new JSONObject();
            }
            String key = json.getString(fieldKey);
            map.put(key == null ? "" : key, json.toJavaObject(tClass));
        }
        return map;
    }

    @Override
    public Map<String, Object> sum(FindHelper findHelper, String[] field, String[] groupField) {
        return aggregate(findHelper, field, groupField, "SUM");
    }

    @Override
    public Map<String, Object> avg(FindHelper findHelper, String[] field, String[] groupField) {
        return aggregate(findHelper, field, groupField, "AVG");
    }

    @Override
    public Map<String, Object> max(FindHelper findHelper, String[] field, String[] groupField) {
        return aggregate(findHelper, field, groupField, "MAX");
    }

    @Override
    public Map<String, Object> min(FindHelper findHelper, String[] field, String[] groupField) {
        return aggregate(findHelper, field, groupField, "MIN");
    }

    @Override
    public <TResult> List<TResult> distinct(FindHelper findHelper, String field, Class<TResult> cla) {
        if (field == null || field.isEmpty()) {
            throw new DomainException("PgFindRepository.distinct 失败：field 不能为空");
        }
        Bound bound = select(findHelper,
                "DISTINCT " + PgFindBuild.groupExpr(field) + " AS v", "");
        List<TResult> list = new ArrayList<>();
        for (Map<String, Object> row : dao.queryList(bound.sql, bound.params)) {
            list.add(convertDistinct(row.get("v"), cla));
        }
        return list;
    }

    // ---------------- 内部 ----------------

    /** SELECT 拼装：基础条件 snapshot_type + 翻译条件。 */
    private Bound select(FindHelper findHelper, String columns, String tail) {
        PgFindBuild.SqlFilter filter = PgFindBuild.build(tClass,
                findHelper == null ? new FindHelper(true) : findHelper);
        StringBuilder sql = new StringBuilder("SELECT ").append(columns)
                .append(" FROM ").append(dao.q(snapshotTable))
                .append(" WHERE snapshot_type=?");
        List<Object> params = new ArrayList<>();
        params.add(tClass.getName());
        if (!filter.sql.isEmpty()) {
            sql.append(" AND ").append(filter.sql);
            params.addAll(filter.params);
        }
        sql.append(tail);
        return new Bound(sql.toString(), params);
    }

    private T deserialize(Map<String, Object> row) {
        return PgDocumentMapper.toEntity(PgDao.str(row.get("snapshot")), tClass);
    }

    private Map<String, Object> aggregate(FindHelper findHelper, String[] field,
                                          String[] groupField, String func) {
        if (field == null || field.length == 0) {
            throw new DomainException("PgFindRepository." + func + " 失败：统计字段 field 不能为空");
        }
        validateGroupField(groupField);
        StringBuilder select = new StringBuilder();
        for (int i = 0; i < groupField.length; i++) {
            if (i > 0) {
                select.append(",");
            }
            select.append(PgFindBuild.groupExpr(groupField[i])).append(" AS g").append(i);
        }
        for (String f : field) {
            select.append(",").append(func).append("(")
                    .append(PgFindBuild.numericExpr(PgFindBuild.jsonExpr(f)))
                    .append(") AS ").append(safeAlias(f));
        }
        Bound bound = select(findHelper, select.toString(), groupClause(groupField.length));
        Map<String, Object> map = new HashMap<>();
        for (Map<String, Object> row : dao.queryList(bound.sql, bound.params)) {
            String group = groupValue(row, groupField.length);
            for (String f : field) {
                @SuppressWarnings("unchecked")
                Map<String, Object> fieldMap =
                        (Map<String, Object>) map.computeIfAbsent(f, k -> new HashMap<>());
                fieldMap.put(group, row.get(safeAlias(f)));
            }
        }
        return map;
    }

    private static String groupClause(int groupCount) {
        StringBuilder sb = new StringBuilder(" GROUP BY ");
        for (int i = 0; i < groupCount; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(i + 1);
        }
        return sb.toString();
    }

    /** 与 Mongo extractGroupValue 一致：多字段用 _ 连接，null 记为 "null"。 */
    private static String groupValue(Map<String, Object> row, int groupCount) {
        if (groupCount == 0) {
            return "_";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groupCount; i++) {
            if (sb.length() > 0) {
                sb.append("_");
            }
            sb.append(String.valueOf(row.get("g" + i)));
        }
        return sb.length() == 0 ? "_" : sb.toString();
    }

    /**
     * 聚合输出列别名：PG 对无引号别名统一折叠为小写（如 {@code AS f_createDate} 读回是
     * {@code f_createdate}），此处主动小写保证 {@code row.get(alias)} 命中。
     * （包可见，供单测回归；仅统计别名使用，不影响 JSON 字段名本身的大小写。）
     */
    static String safeAlias(String field) {
        return ("f_" + field.replace('.', '_').replaceAll("[^A-Za-z0-9_]", "_"))
                .toLowerCase(java.util.Locale.ROOT);
    }

    private void validateGroupField(String[] groupField) {
        if (groupField == null || groupField.length == 0) {
            throw new DomainException("groupField 不能为空：聚合分组必须指定至少一个分组字段，"
                    + "如果确实不需要分组请使用 count(findHelper) 重载");
        }
    }

    @SuppressWarnings("unchecked")
    private <TResult> TResult convertDistinct(Object value, Class<TResult> cla) {
        if (value == null) {
            return null;
        }
        if (cla.isInstance(value)) {
            return (TResult) value;
        }
        try {
            return (TResult) BaseTypeConvert.convertValue(value, cla);
        } catch (Exception e) {
            if (cla == String.class) {
                return (TResult) value.toString();
            }
            throw new DomainException("PgFindRepository.distinct 失败：无法将 " + value + " 转换为 "
                    + cla.getName(), e);
        }
    }

    private static final class Bound {
        final String sql;
        final List<Object> params;

        Bound(String sql, List<Object> params) {
            this.sql = sql;
            this.params = params;
        }
    }
}
