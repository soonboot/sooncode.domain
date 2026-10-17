package com.sooncode.project.core.repository.pgsql;

import com.alibaba.fastjson.JSONObject;
import com.sooncode.project.core.annotations.ModelSnapshot;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.Entity;
import com.sooncode.project.core.repository.mongo.MongoJsonUtil;

import java.util.Map;

/**
 * PGSQL 文档映射定义（对标 {@code MongoDocumentMapper}）。
 *
 * <p>表与 Mongo 集合的对应关系：
 * <ul>
 *   <li>{@code eventMetadata} → {@code event_metadata}</li>
 *   <li>{@code eventSource} → {@code event_source}</li>
 *   <li>{@code eventSnapshot} / 各 {@code @ModelSnapshot} 动态集合 → 同名快照表
 *       （默认 {@code event_snapshot}）</li>
 *   <li>回收站 → {@code snapshot_trash}</li>
 * </ul>
 *
 * <p>实体 / 事件 / 创建者统一存为 JSONB 字符串，序列化与 Mongo 共用
 * {@link MongoJsonUtil#toJsonObject(Object)}（{@code EntityConvert.entityToMap}），
 * 同一份实体在两库的 JSON 形态一致。
 */
final class PgDocumentMapper {

    static final String EVENT_METADATA = com.sooncode.project.core.repository.StoreNames.PG_EVENT_METADATA;
    static final String EVENT_SOURCE = com.sooncode.project.core.repository.StoreNames.PG_EVENT_SOURCE;
    static final String DEFAULT_SNAPSHOT_TABLE = com.sooncode.project.core.repository.StoreNames.PG_EVENT_SNAPSHOT;
    static final String TRASH_TABLE = com.sooncode.project.core.repository.StoreNames.PG_TRASH;

    /** 文档映射版本（写入 event_metadata.ver，Mongo 侧为 15，PG 独立编号）。 */
    static final int VER = 1;

    private PgDocumentMapper() {
    }

    // ---------------- 表名解析 ----------------

    /**
     * 按实体类的 {@code @ModelSnapshot} 解析快照表（对标 Mongo 的动态集合名）。
     * 无注解时回退默认表。
     */
    static String snapshotTable(Class<?> entityClass) {
        if (entityClass != null) {
            ModelSnapshot ann = entityClass.getAnnotation(ModelSnapshot.class);
            if (ann != null) {
                if (ann.value() != null && !ann.value().isEmpty()) {
                    return snapshotTable(ann.value());
                }
                if (ann.collectionName() != null && !ann.collectionName().isEmpty()) {
                    return snapshotTable(ann.collectionName());
                }
            }
        }
        return DEFAULT_SNAPSHOT_TABLE;
    }

    /**
     * 按 {@code modelCollection} 名称解析快照表（兼容 Mongo 传集合名的调用点，
     * 如 {@code BatchOperation.snapshotCollection()} 返回的 {@code StoreNames#MONGO_EVENT_SNAPSHOT}）。
     */
    static String snapshotTable(String modelCollection) {
        if (com.sooncode.project.core.repository.StoreNames.isDefaultSnapshotTable(modelCollection)) {
            return DEFAULT_SNAPSHOT_TABLE;
        }
        return sanitizeTable(modelCollection);
    }

    /**
     * 表名消毒：只保留 {@code [a-z0-9_]}，数字开头补 {@code t_} 前缀，超长截断到 63 字符。
     * 输出可直接拼进带双引号的标识符。
     */
    static String sanitizeTable(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new DomainException("解析快照表名失败：名称不能为空");
        }
        String t = name.trim().toLowerCase().replaceAll("[^a-z0-9_]", "_");
        if (t.isEmpty() || Character.isDigit(t.charAt(0))) {
            t = "t_" + t;
        }
        if (t.length() > 63) {
            t = t.substring(0, 63);
        }
        return t;
    }

    // ---------------- JSON ----------------

    /** 实体 / 事件 / 创建者 → JSONB 字符串（null 返回 null 存 SQL NULL）。 */
    static String toJson(Object object) {
        if (object == null) {
            return null;
        }
        Map<String, Object> map = MongoJsonUtil.toJsonObject(object);
        if (map == null) {
            return null;
        }
        return JSONObject.toJSONString(map);
    }

    /** JSONB 文本 → {@link JSONObject}（null/空 返回 null）。 */
    static JSONObject parseObject(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        return JSONObject.parseObject(json);
    }

    /** JSONB 文本 → {@link Map}（null/空 返回 null）。 */
    static Map<String, Object> parseMap(String json) {
        JSONObject obj = parseObject(json);
        return obj;
    }

    /** JSONB 文本 → 目标实体（对标 Mongo 的 {@code JSONObject.toJavaObject} 反序列化入口）。 */
    static <T> T toEntity(String json, Class<T> cla) {
        JSONObject obj = parseObject(json);
        if (obj == null) {
            return null;
        }
        return obj.toJavaObject(cla);
    }

    /** LIKE 转义：{@code \ % _}。 */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** streamName 规则与 {@code BatchOperation.streamName()} 一致：{@code 类全名-id}。 */
    static String streamName(Class<?> entityClass, String entityId) {
        return entityClass.getName() + "-" + entityId;
    }

    static String streamName(Entity entity) {
        return streamName(entity.getClass(), entity.getId());
    }
}
