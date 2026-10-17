package com.sooncode.project.core.repository.pgsql;

import com.alibaba.fastjson.JSONObject;
import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.model.CheckForConcurrencyException;
import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.model.Entity;
import com.sooncode.project.core.model.EventStream;
import com.sooncode.project.core.model.EventWrapper;
import com.sooncode.project.core.model.IEventSourcingRepository;
import com.sooncode.project.core.model.SnapshotWrapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PGSQL 事件溯源仓储（文档形态，对标 {@code MongoEventSourcingRepository}）。
 *
 * <p>存储形态：
 * <ul>
 *   <li>{@code event_metadata}：{@code id / version / invalid / entity_type / create_date / ver}；</li>
 *   <li>{@code event_source}：{@code id / version / stream_id / event(JSONB) / event_type /
 *       creater(JSONB) / create_date / description(JSONB)}；</li>
 *   <li>快照表：{@code stream_id / snapshot_type / snapshot(JSONB) / create_date}，
 *       表名按 {@code @ModelSnapshot} / {@code modelCollection} 解析。</li>
 * </ul>
 *
 * <p>所有方法均提供「自建连接」与「外带连接」两个版本：外带连接版本供批量仓储
 * 在同一事务里复用，逻辑完全一致。
 */
public class PgEventSourcingRepository implements IEventSourcingRepository {

    final PgDao dao;

    public PgEventSourcingRepository(PgDao dao) {
        if (dao == null) {
            throw new IllegalArgumentException("PgEventSourcingRepository dao 不能为空");
        }
        this.dao = dao;
    }

    // ---------------- 元数据 ----------------

    @Override
    public void addMetadata(EventStream stream) {
        try {
            insertMetadata(null, stream);
        } catch (SQLException e) {
            throw new com.sooncode.project.core.model.DomainException("PGSQL 新增元数据失败:" + stream.getId(), e);
        }
    }

    void insertMetadata(Connection c, EventStream stream) throws SQLException {
        String sql = "INSERT INTO " + dao.q(PgDocumentMapper.EVENT_METADATA)
                + " (id, version, invalid, entity_type, create_date, ver) VALUES (?,?,?,?,?,?)";
        List<Object> p = metadataParams(stream);
        if (c == null) {
            dao.update(sql, p);
        } else {
            dao.update(c, sql, p);
        }
    }

    private static List<Object> metadataParams(EventStream stream) {
        List<Object> p = new ArrayList<>(6);
        p.add(stream.getId());
        p.add(stream.getVersion());
        p.add(stream.getIsInvalid());
        p.add(stream.getEntityType().getName());
        p.add(PgDao.ts(stream.getCreateDate()));
        p.add(PgDocumentMapper.VER);
        return p;
    }

    @Override
    public void updateMetadata(EventStream stream) {
        String sql = "UPDATE " + dao.q(PgDocumentMapper.EVENT_METADATA)
                + " SET version=?, invalid=? WHERE id=?";
        dao.update(sql, params(stream.getVersion(), stream.getIsInvalid(), stream.getId()));
    }

    @Override
    public boolean updateMetadataCAS(EventStream stream, Integer expectedVersion) {
        String sql = "UPDATE " + dao.q(PgDocumentMapper.EVENT_METADATA)
                + " SET version=?, invalid=? WHERE id=? AND invalid=0"
                + (expectedVersion != null ? " AND version=?" : "");
        List<Object> p = params(stream.getVersion(), stream.getIsInvalid(), stream.getId());
        if (expectedVersion != null) {
            p.add(expectedVersion);
        }
        int rows = dao.update(sql, p);
        if (rows == 0) {
            throw new CheckForConcurrencyException(
                    String.format("元数据 CAS 更新失败，预期版本: %s，流: %s", expectedVersion, stream.getId()));
        }
        return true;
    }

    /** 带 CAS 语义的元数据更新（批量仓储用）：命中 0 行返回 false，由调用方抛并发异常。 */
    boolean updateMetadataCAS(Connection c, String streamId, int version, int invalid,
                              Integer expectedVersion) throws SQLException {
        if (expectedVersion == null) {
            // 调用方（PgBatchRepository.casMetadata）恒传非 null 的期望版本；
            // null 会退化成“新版本匹配自身刚写入的值”，恒失败且极难排查，故 fail-fast。
            throw new IllegalArgumentException("PGSQL CAS 更新失败：expectedVersion 不能为 null，流: " + streamId);
        }
        String sql = "UPDATE " + dao.q(PgDocumentMapper.EVENT_METADATA)
                + " SET version=?, invalid=? WHERE id=? AND invalid=0 AND version=?";
        int rows = dao.update(c, sql, params(version, invalid, streamId, expectedVersion));
        return rows > 0;
    }

    @Override
    public EventStream loadMetadata(String streamName) {
        try (Connection c = dao.connection()) {
            return loadMetadata(c, streamName);
        } catch (SQLException e) {
            throw new com.sooncode.project.core.model.DomainException("PGSQL 读取元数据失败:" + streamName, e);
        }
    }

    EventStream loadMetadata(Connection c, String streamName) throws SQLException {
        Map<String, Object> row = dao.queryOne(c,
                "SELECT id, version, invalid, entity_type, create_date FROM "
                        + dao.q(PgDocumentMapper.EVENT_METADATA) + " WHERE id=?",
                params(streamName));
        return toEventStream(streamName, row);
    }

    static EventStream toEventStream(String streamName, Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        try {
            return new EventStream(
                    (String) row.get("id"),
                    ((Number) row.get("version")).intValue(),
                    ((Number) row.get("invalid")).intValue(),
                    Class.forName((String) row.get("entity_type")),
                    PgDao.date(row.get("create_date")));
        } catch (Exception ex) {
            throw new com.sooncode.project.core.model.DomainException("读取元数据失败:" + streamName, ex);
        }
    }

    // ---------------- 事件流 ----------------

    @Override
    public void saveStream(EventWrapper wrapper) {
        try (Connection c = dao.connection()) {
            insertEvent(c, wrapper);
        } catch (SQLException e) {
            throw new com.sooncode.project.core.model.DomainException("PGSQL 保存事件失败:" + wrapper.getId(), e);
        }
    }

    void insertEvent(Connection c, EventWrapper wrapper) throws SQLException {
        String sql = "INSERT INTO " + dao.q(PgDocumentMapper.EVENT_SOURCE)
                + " (id, version, stream_id, event, event_type, creater, create_date, description)"
                + " VALUES (?,?,?,?::jsonb,?,?,?,?::jsonb)";
        List<Object> p = new ArrayList<>(8);
        p.add(wrapper.getId());
        p.add(wrapper.getEventVersion());
        p.add(wrapper.getEventStreamId());
        p.add(PgDocumentMapper.toJson(wrapper.getEvent()));
        p.add(wrapper.getEventType().getName());
        p.add(PgDocumentMapper.toJson(wrapper.getCreater()));
        p.add(PgDao.ts(wrapper.getCreateDate()));
        p.add(PgDocumentMapper.toJson(wrapper.getDescription()));
        dao.update(c, sql, p);
    }

    @Override
    public List<EventWrapper> getStream(String streamName, Integer fromVersion, Integer toVersion) {
        StringBuilder sql = new StringBuilder("SELECT id, version, stream_id, event, event_type,"
                + " creater, create_date, description FROM "
                + dao.q(PgDocumentMapper.EVENT_SOURCE) + " WHERE stream_id=?");
        List<Object> p = new ArrayList<>();
        p.add(streamName);
        if (fromVersion != null) {
            sql.append(" AND version>=?");
            p.add(fromVersion);
        }
        if (toVersion != null) {
            sql.append(" AND version<=?");
            p.add(toVersion);
        }
        sql.append(" ORDER BY version ASC");
        List<Map<String, Object>> rows = dao.queryList(sql.toString(), p);
        List<EventWrapper> events = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            events.add(toEventWrapper(row));
        }
        return events;
    }

    @Override
    public Page<EventWrapper> getStream(String modelType, String eventType, String creater,
                                       int pageSize, int pageIndex) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> p = new ArrayList<>();
        if (modelType != null && !modelType.isEmpty()) {
            where.append(" AND stream_id LIKE ? ESCAPE '\\'");
            p.add("%" + PgDocumentMapper.escapeLike(modelType) + "%");
        }
        if (eventType != null && !eventType.isEmpty()) {
            where.append(" AND event_type=?");
            p.add(eventType);
        }
        if (creater != null && !creater.isEmpty()) {
            where.append(" AND creater->>'id'=?");
            p.add(creater);
        }
        String table = dao.q(PgDocumentMapper.EVENT_SOURCE);
        String base = "SELECT id, version, stream_id, event, event_type, creater, create_date, description"
                + " FROM " + table + where + " ORDER BY create_date DESC LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<>(p);
        pageParams.add(pageSize);
        pageParams.add((long) pageIndex * pageSize);
        List<EventWrapper> list = new ArrayList<>();
        for (Map<String, Object> row : dao.queryList(base.toString(), pageParams)) {
            list.add(toEventWrapper(row));
        }
        long total = dao.queryLong("SELECT COUNT(*) FROM " + table + where, p);
        Page<EventWrapper> page = new Page<>();
        page.setPageSize(pageSize);
        page.setPageIndex(pageIndex);
        page.setTotalElements(total);
        page.setContent(list);
        return page;
    }

    static EventWrapper toEventWrapper(Map<String, Object> row) {
        try {
            String eventJson = PgDao.str(row.get("event"));
            JSONObject json = PgDocumentMapper.parseObject(eventJson);
            if (json == null) {
                json = new JSONObject();
            }
            DomainEvent ev = (DomainEvent) json.toJavaObject(Class.forName((String) row.get("event_type")));
            Map<String, Object> creater = PgDocumentMapper.parseMap(PgDao.str(row.get("creater")));
            Map<String, Object> description = PgDocumentMapper.parseMap(PgDao.str(row.get("description")));
            return new EventWrapper(ev,
                    ((Number) row.get("version")).intValue(),
                    PgDao.date(row.get("create_date")),
                    (String) row.get("stream_id"),
                    creater == null ? new HashMap<>() : creater,
                    description == null ? new HashMap<>() : description);
        } catch (Exception ex) {
            throw new com.sooncode.project.core.model.DomainException(
                    "读取事件流失败:" + row.get("stream_id"), ex);
        }
    }

    // ---------------- 快照 ----------------

    @Override
    public void saveSnapshotWrapper(SnapshotWrapper wrapper, String modelCollection) {
        String table = PgDocumentMapper.snapshotTable(modelCollection);
        dao.ensureSnapshotTable(table);
        try (Connection c = dao.connection()) {
            upsertSnapshot(c, table, wrapper);
        } catch (SQLException e) {
            throw new com.sooncode.project.core.model.DomainException(
                    "PGSQL 保存快照失败:" + wrapper.getStreamId(), e);
        }
    }

    void upsertSnapshot(Connection c, String table, SnapshotWrapper wrapper) throws SQLException {
        String sql = "INSERT INTO " + dao.q(table)
                + " (stream_id, snapshot_type, snapshot, create_date) VALUES (?,?,?::jsonb,?)"
                + " ON CONFLICT (stream_id) DO UPDATE SET snapshot_type=EXCLUDED.snapshot_type,"
                + " snapshot=EXCLUDED.snapshot, create_date=EXCLUDED.create_date";
        dao.update(c, sql, params(wrapper.getStreamId(),
                wrapper.getSnapshotType().getName(),
                PgDocumentMapper.toJson(wrapper.getSnapshot()),
                PgDao.ts(wrapper.getCreateDate())));
    }

    @Override
    public void deleteSnapshotWrapper(String streamId, String modelCollection) {
        String table = PgDocumentMapper.snapshotTable(modelCollection);
        dao.update("DELETE FROM " + dao.q(table) + " WHERE stream_id=?", params(streamId));
    }

    void deleteSnapshot(Connection c, String table, String streamId) throws SQLException {
        dao.update(c, "DELETE FROM " + dao.q(table) + " WHERE stream_id=?", params(streamId));
    }

    @Override
    public SnapshotWrapper getSnapshotWrapper(String streamId, String modelCollection) {
        String table = PgDocumentMapper.snapshotTable(modelCollection);
        Map<String, Object> row = dao.queryOne(
                "SELECT stream_id, snapshot_type, snapshot, create_date FROM " + dao.q(table)
                        + " WHERE stream_id=?",
                params(streamId));
        return toSnapshotWrapper(row);
    }

    Map<String, Object> findSnapshotRow(Connection c, String table, String streamId) throws SQLException {
        return dao.queryOne(c,
                "SELECT stream_id, snapshot_type, snapshot, create_date FROM " + dao.q(table)
                        + " WHERE stream_id=?",
                params(streamId));
    }

    static SnapshotWrapper toSnapshotWrapper(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        try {
            Class<?> cla = Class.forName((String) row.get("snapshot_type"));
            Entity en = (Entity) PgDocumentMapper.toEntity(PgDao.str(row.get("snapshot")), cla);
            return new SnapshotWrapper((String) row.get("stream_id"), en,
                    PgDao.date(row.get("create_date")));
        } catch (Exception ex) {
            throw new com.sooncode.project.core.model.DomainException(
                    "读取快照失败:" + row.get("stream_id"), ex);
        }
    }

    @Override
    public List<SnapshotWrapper> getSnapshotWrapperList(String streamType, String modelCollection) {
        String table = PgDocumentMapper.snapshotTable(modelCollection);
        List<Map<String, Object>> rows = dao.queryList(
                "SELECT stream_id, snapshot_type, snapshot, create_date FROM " + dao.q(table)
                        + " WHERE snapshot_type=? ORDER BY create_date DESC",
                params(streamType));
        List<SnapshotWrapper> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            list.add(toSnapshotWrapper(row));
        }
        return list;
    }

    @Override
    public Map<String, Object> getSnapshotDoc(String streamType, String modelCollection) {
        String table = PgDocumentMapper.snapshotTable(modelCollection);
        Map<String, Object> row = dao.queryOne(
                "SELECT snapshot FROM " + dao.q(table) + " WHERE snapshot_type=? LIMIT 1",
                params(streamType));
        if (row == null) {
            return null;
        }
        return PgDocumentMapper.parseMap(PgDao.str(row.get("snapshot")));
    }

    // ---------------- 小工具 ----------------

    static List<Object> params(Object... values) {
        List<Object> p = new ArrayList<>(values.length);
        Collections.addAll(p, values);
        return p;
    }

    /** 当前连接是否已存在某快照（批量 ADD 幂等校验用）。 */
    boolean snapshotExists(Connection c, String table, String streamId) throws SQLException {
        Map<String, Object> row = dao.queryOne(c,
                "SELECT 1 AS one FROM " + dao.q(table) + " WHERE stream_id=?", params(streamId));
        return row != null;
    }

    /** 当前时间（批量 ADD 建流用，与 Mongo 侧 new Date() 语义一致）。 */
    static Date now() {
        return new Date();
    }
}
