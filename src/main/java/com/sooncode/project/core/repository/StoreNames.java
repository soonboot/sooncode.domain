package com.sooncode.project.core.repository;

/**
 * 文档 / 表名称的统一定义。
 *
 * <p>Mongo 集合名与 PGSQL 表名在此集中声明，唯一事实来源：
 * <ul>
 *   <li>Mongo 侧：{@code eventMetadata / eventSource / eventSnapshot / snapshotTrash}；</li>
 *   <li>PGSQL 侧：{@code event_metadata / event_source / event_snapshot / snapshot_trash}。</li>
 * </ul>
 * 各仓储实现（{@code MongoDocumentMapper / PgDocumentMapper}）的常量均委托到这里，
 * 业务代码禁止再散落硬编码字符串字面量。
 */
public final class StoreNames {

    /** Mongo：事件元数据集合。 */
    public static final String MONGO_EVENT_METADATA = "eventMetadata";
    /** Mongo：事件源集合。 */
    public static final String MONGO_EVENT_SOURCE = "eventSource";
    /** Mongo：默认快照集合（无 {@code @ModelSnapshot} 时的回退）。 */
    public static final String MONGO_EVENT_SNAPSHOT = "eventSnapshot";
    /** Mongo：回收站集合（单条链路与批量链路共用）。 */
    public static final String MONGO_TRASH = "snapshotTrash";

    /** PGSQL：事件元数据表。 */
    public static final String PG_EVENT_METADATA = "event_metadata";
    /** PGSQL：事件源表。 */
    public static final String PG_EVENT_SOURCE = "event_source";
    /** PGSQL：默认快照表（无 {@code @ModelSnapshot} 时的回退）。 */
    public static final String PG_EVENT_SNAPSHOT = "event_snapshot";
    /** PGSQL：回收站表（单表 {@code snapshot_trash}）。 */
    public static final String PG_TRASH = "snapshot_trash";

    private StoreNames() {
    }

    /**
     * 是否为 Mongo 默认快照集合（null/空串按默认处理）。
     */
    public static boolean isDefaultMongoSnapshotCollection(String name) {
        return name == null || name.isEmpty() || MONGO_EVENT_SNAPSHOT.equalsIgnoreCase(name);
    }

    /**
     * 是否为默认快照表（兼容 Mongo 集合名透传，如 {@code BatchOperation.snapshotCollection()}
     * 返回的 {@code "eventSnapshot"}，以及 PG 表名本身；null/空串按默认处理）。
     */
    public static boolean isDefaultSnapshotTable(String name) {
        return name == null || name.trim().isEmpty()
                || MONGO_EVENT_SNAPSHOT.equalsIgnoreCase(name)
                || PG_EVENT_SNAPSHOT.equalsIgnoreCase(name);
    }
}
