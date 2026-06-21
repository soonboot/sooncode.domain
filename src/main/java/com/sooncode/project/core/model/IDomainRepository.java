package com.sooncode.project.core.model;

import com.sooncode.project.core.finder.Page;

import java.util.List;
import java.util.Map;

/**
 * 基础领域模型存储库
 * @param <T>
 */
public interface IDomainRepository<T extends DomainModel> {
    T findByID(String id,Class<T> tClass);
    void add(T entity);
    void add(T entity,IGenerateReport report);
    void add(T entity,IGenerateReport report,boolean monitor);
    void save(T entity);
    void save(T entity,IGenerateReport report);
    void save(T entity,IGenerateReport report,boolean monitor);
    void delete(T entity);
    void delete(T entity,IGenerateReport report);
    void delete(T entity,IGenerateReport report,boolean monitor);
    Page<EventWrapper> getEventStream(Class modelClass, Class cla, Creater creater, int pageSize, int pageIndex);
    T replay(String id, Class<T> tClass, int toVersion);
    void saveSnapshot(Entity entity);
    void deleteSnapshot(Entity entity);
    List<T> getSnapshotList(Class<T> tClass);
}
