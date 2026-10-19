package com.sooncode.project.core.repository.pgsql;

import com.alibaba.fastjson.JSONObject;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.trash.ITrashRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PGSQL 回收站仓储（对标 {@code MongoTrashRepository}）。
 *
 * <p>单表 {@code snapshot_trash}：
 * {@code stream_id / entity_id / entity_type / snapshot_doc(JSONB) / delete_time}。
 * 其中 {@code snapshot_doc} 存的是原快照行的完整镜像
 * {@code {streamId, snapshotType, snapshot:{...}, createDate}}，
 * 键名与 Mongo 的 trash 文档保持一致，上层 {@code Trash} 无需改动。
 */
public class PgTrashRepository implements ITrashRepository {

    final PgDao dao;

    public PgTrashRepository(PgDao dao) {
        if (dao == null) {
            throw new DomainException("PgTrashRepository 构造失败：dao 不能为空");
        }
        this.dao = dao;
    }

    @Override
    public void save(Class<?> entityClass, String streamId, String entityId) {
        String table = PgDocumentMapper.snapshotTable(entityClass);
        try (Connection c = dao.connection()) {
            save(c, entityClass, streamId, entityId);
        } catch (SQLException e) {
            throw new DomainException("PGSQL 回收站保存失败:" + streamId, e);
        }
    }

    /** 事务内版本：供批量删除复用同一连接。 */
    void save(Connection c, Class<?> entityClass, String streamId, String entityId) throws SQLException {
        String table = PgDocumentMapper.snapshotTable(entityClass);
        Map<String, Object> row = dao.queryOne(c,
                "SELECT stream_id, snapshot_type, snapshot, create_date FROM " + dao.q(table)
                        + " WHERE stream_id=?",
                PgEventSourcingRepository.params(streamId));
        if (row == null) {
            return;
        }
        JSONObject doc = new JSONObject();
        doc.put("streamId", row.get("stream_id"));
        doc.put("snapshotType", row.get("snapshot_type"));
        JSONObject snapshot = PgDocumentMapper.parseObject(PgDao.str(row.get("snapshot")));
        doc.put("snapshot", snapshot == null ? new JSONObject() : snapshot);
        Object createDate = row.get("create_date");
        Date createTime = PgDao.date(createDate);
        doc.put("createDate", createTime == null ? null : createTime.getTime());
        String sql = "INSERT INTO " + dao.q(PgDocumentMapper.TRASH_TABLE)
                + " (stream_id, entity_id, entity_type, snapshot_doc, delete_time)"
                + " VALUES (?,?,?,?::jsonb,?)"
                + " ON CONFLICT (stream_id) DO UPDATE SET entity_id=EXCLUDED.entity_id,"
                + " entity_type=EXCLUDED.entity_type, snapshot_doc=EXCLUDED.snapshot_doc,"
                + " delete_time=EXCLUDED.delete_time";
        dao.update(c, sql, PgEventSourcingRepository.params(streamId, entityId,
                entityClass.getName(), doc.toJSONString(), PgDao.ts(new Date())));
    }

    @Override
    public Map<String, Object> findByStreamId(String streamId) {
        try (Connection c = dao.connection()) {
            return toTrashMap(dao.queryOne(c,
                    "SELECT stream_id, entity_id, entity_type, snapshot_doc, delete_time FROM "
                            + dao.q(PgDocumentMapper.TRASH_TABLE) + " WHERE stream_id=?",
                    PgEventSourcingRepository.params(streamId)));
        } catch (SQLException e) {
            throw new DomainException("PGSQL 回收站查询失败:" + streamId, e);
        }
    }

    static Map<String, Object> toTrashMap(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("streamId", row.get("stream_id"));
        map.put("entityId", row.get("entity_id"));
        map.put("entityType", row.get("entity_type"));
        map.put("snapshotDoc", PgDocumentMapper.parseMap(PgDao.str(row.get("snapshot_doc"))));
        map.put("deleteTime", PgDao.date(row.get("delete_time")));
        return map;
    }

    @Override
    public Map<String, Object> restoreSnapshot(String streamId, Class<?> entityClass) {
        // DDL 在事务外先建表（PG DDL 有事务副作用，且 ensureSnapshotTable 自带幂等），
        // 恢复的“写回快照 + 删除回收站”必须在同一连接同一事务内原子完成，
        // 否则中途失败会留下“已恢复 + 回收站未删”的脏状态。
        String table = PgDocumentMapper.snapshotTable(entityClass);
        dao.ensureSnapshotTable(table);
        try (Connection c = dao.connection()) {
            boolean oldAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                Map<String, Object> row = dao.queryOne(c,
                        "SELECT snapshot_doc FROM " + dao.q(PgDocumentMapper.TRASH_TABLE)
                                + " WHERE stream_id=?",
                        PgEventSourcingRepository.params(streamId));
                if (row == null) {
                    c.rollback();
                    return null;
                }
                Map<String, Object> snapshotDoc =
                        PgDocumentMapper.parseMap(PgDao.str(row.get("snapshot_doc")));
                if (snapshotDoc == null) {
                    c.rollback();
                    return null;
                }
                dao.update(c, "INSERT INTO " + dao.q(table)
                        + " (stream_id, snapshot_type, snapshot, create_date) VALUES (?,?,?::jsonb,?)"
                        + " ON CONFLICT (stream_id) DO UPDATE SET snapshot_type=EXCLUDED.snapshot_type,"
                        + " snapshot=EXCLUDED.snapshot, create_date=EXCLUDED.create_date"
                        + " WHERE " + dao.q(table) + ".snapshot IS DISTINCT FROM EXCLUDED.snapshot"
                        + " OR " + dao.q(table) + ".snapshot_type IS DISTINCT FROM EXCLUDED.snapshot_type"
                        + " OR " + dao.q(table) + ".create_date IS DISTINCT FROM EXCLUDED.create_date",
                        PgEventSourcingRepository.params(
                                String.valueOf(snapshotDoc.get("streamId")),
                                String.valueOf(snapshotDoc.get("snapshotType")),
                                snapshotToJson(snapshotDoc.get("snapshot")),
                                toTimestamp(snapshotDoc.get("createDate"))));
                dao.update(c, "DELETE FROM " + dao.q(PgDocumentMapper.TRASH_TABLE)
                        + " WHERE stream_id=?",
                        PgEventSourcingRepository.params(streamId));
                c.commit();
                return snapshotDoc;
            } catch (Exception e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            } finally {
                try {
                    c.setAutoCommit(oldAuto);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            throw new DomainException("PGSQL 回收站恢复失败:" + streamId, e);
        }
    }

    private static String snapshotToJson(Object snapshot) {
        if (snapshot == null) {
            return "{}";
        }
        if (snapshot instanceof String) {
            return (String) snapshot;
        }
        return JSONObject.toJSONString(snapshot);
    }

    private static java.sql.Timestamp toTimestamp(Object createDate) {
        if (createDate == null) {
            return PgDao.ts(new Date());
        }
        if (createDate instanceof Date) {
            return new java.sql.Timestamp(((Date) createDate).getTime());
        }
        Date parsed = null;
        try {
            parsed = PgDao.date(createDate);
        } catch (Exception ignored) {
            // 脏数据回退为当前时间（保持原有容错行为）
        }
        return PgDao.ts(parsed == null ? new Date() : parsed);
    }

    @Override
    public List<Map<String, Object>> listByEntityType(String entityType, int pageIndex, int pageSize) {
        List<Map<String, Object>> rows = dao.queryList(
                "SELECT stream_id, entity_id, entity_type, snapshot_doc, delete_time FROM "
                        + dao.q(PgDocumentMapper.TRASH_TABLE)
                        + " WHERE entity_type=? ORDER BY delete_time DESC LIMIT ? OFFSET ?",
                PgEventSourcingRepository.params(entityType, pageSize, (long) pageIndex * pageSize));
        return toTrashMaps(rows);
    }

    @Override
    public long countByEntityType(String entityType) {
        return dao.queryLong("SELECT COUNT(*) FROM " + dao.q(PgDocumentMapper.TRASH_TABLE)
                + " WHERE entity_type=?", PgEventSourcingRepository.params(entityType));
    }

    @Override
    public List<Map<String, Object>> listAll(int pageIndex, int pageSize) {
        List<Map<String, Object>> rows = dao.queryList(
                "SELECT stream_id, entity_id, entity_type, snapshot_doc, delete_time FROM "
                        + dao.q(PgDocumentMapper.TRASH_TABLE)
                        + " ORDER BY delete_time DESC LIMIT ? OFFSET ?",
                PgEventSourcingRepository.params(pageSize, (long) pageIndex * pageSize));
        return toTrashMaps(rows);
    }

    @Override
    public long countAll() {
        return dao.queryLong("SELECT COUNT(*) FROM " + dao.q(PgDocumentMapper.TRASH_TABLE), null);
    }

    @Override
    public void removeByStreamId(String streamId) {
        dao.update("DELETE FROM " + dao.q(PgDocumentMapper.TRASH_TABLE) + " WHERE stream_id=?",
                PgEventSourcingRepository.params(streamId));
    }

    private static List<Map<String, Object>> toTrashMaps(List<Map<String, Object>> rows) {
        List<Map<String, Object>> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            list.add(toTrashMap(row));
        }
        return list;
    }
}
