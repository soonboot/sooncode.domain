package com.sooncode.project.core.trash;

import com.alibaba.fastjson.JSONObject;
import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.IEventStore;
import com.sooncode.project.core.repository.mongo.MongoTrashRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Trash 业务门面类
 *
 * <p>封装 Trash 的操作逻辑（恢复、查询、统计），通过 {@link ITrashRepository}
 * 与具体数据库实现解耦。未显式注入数据层实现时，默认使用框架的 MongoDB 连接
 * （{@link MongoTrashRepository#fromDefault()}）。</p>
 */
public class Trash {
    private final IEventStore eventStore;
    private ITrashRepository trashRepository;

    /**
     * 构造器，自动配置数据层实现。
     *
     * @param eventStore 事件存储器对象
     */
    public Trash(IEventStore eventStore) {
        this.eventStore = eventStore;
        this.trashRepository = MongoTrashRepository.fromDefault();
    }

    /**
     * 构造器，显式指定数据层实现。
     *
     * @param eventStore       事件存储器对象
     * @param trashRepository Trash 数据层实现
     */
    public Trash(IEventStore eventStore, ITrashRepository trashRepository) {
        this.eventStore = eventStore;
        this.trashRepository = trashRepository;
    }

    /**
     * 显式设置数据层实现（可选，不设置时使用框架默认 MongoDB 连接）。
     *
     * @param trashRepository Trash 数据层实现
     */
    public void setTrashRepository(ITrashRepository trashRepository) {
        this.trashRepository = trashRepository;
    }

    /** 返回当前是否配置了实际的 Trash 存储实现。 */
    public boolean isEnabled() {
        return trashRepository != null;
    }

    /**
     * 删除实体时，将完整 snapshot 保存到 Trash。
     *
     * @param entityClass 实体类型
     * @param streamName  streamId
     * @param entityId    实体主键
     */
    public void saveToTrash(Class<?> entityClass, String streamName, String entityId) {
        if (trashRepository == null) return;
        trashRepository.save(entityClass, streamName, entityId);
    }

    /**
     * 恢复 Trash 中的实体。
     *
     * @param entityId 实体主键
     * @param tClass   实体类型
     * @param <T>      实体类型
     * @return 恢复后的实体
     */
    public <T> T restore(String entityId, Class<T> tClass) {
        ensureTrashRepository();
        String streamName = streamNameFor(tClass, entityId);
        Map<String, Object> trashDoc = trashRepository.findByStreamId(streamName);
        if (trashDoc == null)
            throw new DomainException("Trash 未找到数据:" + streamName);
        Map<String, Object> snapshotDoc = (Map<String, Object>) trashDoc.get("snapshotDoc");
        eventStore.reactivate(streamName);
        trashRepository.restoreSnapshot(streamName, tClass);
        Map<String, Object> snapshot = (Map<String, Object>) snapshotDoc.get("snapshot");
        if (snapshot == null)
            throw new DomainException("Trash 快照数据异常:" + streamName);
        JSONObject jsonObject = new JSONObject(snapshot);
        T entity = jsonObject.toJavaObject(tClass);
        if (entity instanceof DomainModel) {
            ((DomainModel) entity).markStored();
        }
        return entity;
    }

    /**
     * 分页查询指定实体类型的 Trash 记录。
     *
     * @param tClass    实体类型
     * @param pageIndex 页码（从 0 开始）
     * @param pageSize  每页条数
     * @return 分页结果
     */
    public <T> Page<TrashRecord> listTrash(Class<T> tClass, int pageIndex, int pageSize) {
        ensureTrashRepository();
        String entityType = tClass.getName();
        List<Map<String, Object>> docs = trashRepository.listByEntityType(entityType, pageIndex, pageSize);
        long total = trashRepository.countByEntityType(entityType);
        return buildTrashPage(docs, total, pageIndex, pageSize);
    }

    /**
     * 统计指定实体类型的回收站记录数。
     *
     * @param tClass 实体类型
     * @return 记录数
     */
    public <T> long countTrash(Class<T> tClass) {
        ensureTrashRepository();
        return trashRepository.countByEntityType(tClass.getName());
    }

    /**
     * 分页查询全部 Trash 记录。
     *
     * @param pageIndex 页码（从 0 开始）
     * @param pageSize  每页条数
     * @return 分页结果
     */
    public Page<TrashRecord> listAllTrash(int pageIndex, int pageSize) {
        ensureTrashRepository();
        List<Map<String, Object>> docs = trashRepository.listAll(pageIndex, pageSize);
        long total = trashRepository.countAll();
        return buildTrashPage(docs, total, pageIndex, pageSize);
    }

    /**
     * 统计全部回收站记录数。
     *
     * @return 记录数
     */
    public long countAllTrash() {
        ensureTrashRepository();
        return trashRepository.countAll();
    }

    private void ensureTrashRepository() {
        if (trashRepository == null) {
            trashRepository = MongoTrashRepository.fromDefault();
        }
        if (trashRepository == null)
            throw new DomainException("TrashRepository not configured");
    }

    private Page<TrashRecord> buildTrashPage(List<Map<String, Object>> docs, long total, int pageIndex, int pageSize) {
        List<TrashRecord> records = new ArrayList<>();
        for (Map<String, Object> doc : docs) {
            TrashRecord record = new TrashRecord();
            record.setId(String.valueOf(doc.get("_id")));
            record.setStreamId((String) doc.get("streamId"));
            record.setEntityId((String) doc.get("entityId"));
            record.setEntityType((String) doc.get("entityType"));
            record.setSnapshotDoc((Map<String, Object>) doc.get("snapshotDoc"));
            record.setDeleteTime((java.util.Date) doc.get("deleteTime"));
            records.add(record);
        }
        Page<TrashRecord> page = new Page<>();
        page.setTotalElements(total);
        page.setPageIndex(pageIndex);
        page.setPageSize(pageSize);
        page.setContent(records);
        return page;
    }

    private String streamNameFor(Class c, String id) {
        return String.format("%s-%s", c.getName(), id);
    }
}
