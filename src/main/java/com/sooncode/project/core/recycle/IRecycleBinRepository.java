package com.sooncode.project.core.recycle;

import java.util.List;
import java.util.Map;

/**
 * 回收站存储库接口
 *
 * <p>数据库无关，所有方法签名使用 {@link Map}/{@link List} 等通用类型，
 * 以便未来扩展 MySQL、PostgreSQL 等其他数据库实现。</p>
 */
public interface IRecycleBinRepository {

    /**
     * 从原 snapshot 集合读取完整 doc 并写入回收站。
     *
     * @param entityClass 实体类型
     * @param streamId    streamId
     * @param entityId    实体主键
     */
    void save(Class<?> entityClass, String streamId, String entityId);

    /**
     * 查询回收站中指定 streamId 的记录。
     *
     * @param streamId streamId
     * @return 回收站记录，不存在返回 null
     */
    Map<String, Object> findByStreamId(String streamId);

    /**
     * 将回收站快照恢复到原 snapshot 集合，并删除回收站记录。
     *
     * @param streamId    streamId
     * @param entityClass 实体类型
     * @return 恢复的快照 doc，不存在返回 null
     */
    Map<String, Object> restoreSnapshot(String streamId, Class<?> entityClass);

    /**
     * 分页查询指定实体类型的回收站记录。
     *
     * @param entityType 实体类型全限定名
     * @param pageIndex  页码（从 0 开始）
     * @param pageSize   每页条数
     * @return 回收站记录列表
     */
    List<Map<String, Object>> listByEntityType(String entityType, int pageIndex, int pageSize);

    /**
     * 统计指定实体类型的回收站记录数。
     *
     * @param entityType 实体类型全限定名
     * @return 记录数
     */
    long countByEntityType(String entityType);

    /**
     * 分页查询全部回收站记录。
     *
     * @param pageIndex 页码（从 0 开始）
     * @param pageSize  每页条数
     * @return 回收站记录列表
     */
    List<Map<String, Object>> listAll(int pageIndex, int pageSize);

    /**
     * 统计全部回收站记录数。
     *
     * @return 记录数
     */
    long countAll();

    /**
     * 根据 streamId 删除回收站记录。
     *
     * @param streamId streamId
     */
    void removeByStreamId(String streamId);
}
