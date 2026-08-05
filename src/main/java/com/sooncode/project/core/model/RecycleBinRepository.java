package com.sooncode.project.core.model;

import com.alibaba.fastjson.JSONObject;
import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.recycle.IRecycleBinRepository;
import com.sooncode.project.core.recycle.RecycleBinRecord;
import com.sooncode.project.core.repository.mongo.MongoRecycleBinRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 回收站业务门面类
 *
 * <p>封装回收站的操作逻辑（恢复、查询、统计），通过 {@link IRecycleBinRepository}
 * 与具体数据库实现解耦。未显式注入数据层实现时，默认使用框架的 MongoDB 连接
 * （{@link MongoRecycleBinRepository#fromDefault()}）。</p>
 */
public class RecycleBinRepository {
    private final IEventStore eventStore;
    private IRecycleBinRepository recycleRepository;

    /**
     * 构造器，自动配置数据层实现。
     *
     * @param eventStore 事件存储器对象
     */
    public RecycleBinRepository(IEventStore eventStore) {
        this.eventStore = eventStore;
        this.recycleRepository = MongoRecycleBinRepository.fromDefault();
    }

    /**
     * 构造器，显式指定数据层实现。
     *
     * @param eventStore       事件存储器对象
     * @param recycleRepository 回收站数据层实现
     */
    public RecycleBinRepository(IEventStore eventStore, IRecycleBinRepository recycleRepository) {
        this.eventStore = eventStore;
        this.recycleRepository = recycleRepository;
    }

    /**
     * 显式设置数据层实现（可选，不设置时使用框架默认 MongoDB 连接）。
     *
     * @param recycleRepository 回收站数据层实现
     */
    public void setRecycleRepository(IRecycleBinRepository recycleRepository) {
        this.recycleRepository = recycleRepository;
    }

    /**
     * 删除实体时，将完整 snapshot 保存到回收站。
     *
     * @param entityClass 实体类型
     * @param streamName  streamId
     * @param entityId    实体主键
     */
    public void saveToRecycleBin(Class<?> entityClass, String streamName, String entityId) {
        if (recycleRepository == null) return;
        recycleRepository.save(entityClass, streamName, entityId);
    }

    /**
     * 恢复回收站中的实体。
     *
     * @param entityId 实体主键
     * @param tClass   实体类型
     * @param <T>      实体类型
     * @return 恢复后的实体
     */
    public <T> T restore(String entityId, Class<T> tClass) {
        ensureRecycleRepository();
        String streamName = streamNameFor(tClass, entityId);
        Map<String, Object> recycleDoc = recycleRepository.findByStreamId(streamName);
        if (recycleDoc == null)
            throw new DomainException("回收站未找到数据:" + streamName);
        Map<String, Object> snapshotDoc = (Map<String, Object>) recycleDoc.get("snapshotDoc");
        eventStore.reactivate(streamName);
        recycleRepository.restoreSnapshot(streamName, tClass);
        Map<String, Object> snapshot = (Map<String, Object>) snapshotDoc.get("snapshot");
        if (snapshot == null)
            throw new DomainException("回收站快照数据异常:" + streamName);
        JSONObject jsonObject = new JSONObject(snapshot);
        T entity = jsonObject.toJavaObject(tClass);
        if (entity instanceof DomainModel) {
            ((DomainModel) entity).markStored();
        }
        return entity;
    }

    /**
     * 分页查询指定实体类型的回收站记录。
     *
     * @param tClass    实体类型
     * @param pageIndex 页码（从 0 开始）
     * @param pageSize  每页条数
     * @return 分页结果
     */
    public <T> Page<RecycleBinRecord> listTrash(Class<T> tClass, int pageIndex, int pageSize) {
        ensureRecycleRepository();
        String entityType = tClass.getName();
        List<Map<String, Object>> docs = recycleRepository.listByEntityType(entityType, pageIndex, pageSize);
        long total = recycleRepository.countByEntityType(entityType);
        return buildTrashPage(docs, total, pageIndex, pageSize);
    }

    /**
     * 统计指定实体类型的回收站记录数。
     *
     * @param tClass 实体类型
     * @return 记录数
     */
    public <T> long countTrash(Class<T> tClass) {
        ensureRecycleRepository();
        return recycleRepository.countByEntityType(tClass.getName());
    }

    /**
     * 分页查询全部回收站记录。
     *
     * @param pageIndex 页码（从 0 开始）
     * @param pageSize  每页条数
     * @return 分页结果
     */
    public Page<RecycleBinRecord> listAllTrash(int pageIndex, int pageSize) {
        ensureRecycleRepository();
        List<Map<String, Object>> docs = recycleRepository.listAll(pageIndex, pageSize);
        long total = recycleRepository.countAll();
        return buildTrashPage(docs, total, pageIndex, pageSize);
    }

    /**
     * 统计全部回收站记录数。
     *
     * @return 记录数
     */
    public long countAllTrash() {
        ensureRecycleRepository();
        return recycleRepository.countAll();
    }

    private void ensureRecycleRepository() {
        if (recycleRepository == null) {
            recycleRepository = MongoRecycleBinRepository.fromDefault();
        }
        if (recycleRepository == null)
            throw new DomainException("RecycleBinRepository not configured");
    }

    private Page<RecycleBinRecord> buildTrashPage(List<Map<String, Object>> docs, long total, int pageIndex, int pageSize) {
        List<RecycleBinRecord> records = new ArrayList<>();
        for (Map<String, Object> doc : docs) {
            RecycleBinRecord record = new RecycleBinRecord();
            record.setId(String.valueOf(doc.get("_id")));
            record.setStreamId((String) doc.get("streamId"));
            record.setEntityId((String) doc.get("entityId"));
            record.setEntityType((String) doc.get("entityType"));
            record.setSnapshotDoc((Map<String, Object>) doc.get("snapshotDoc"));
            record.setDeleteTime((java.util.Date) doc.get("deleteTime"));
            records.add(record);
        }
        Page<RecycleBinRecord> page = new Page<>();
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
