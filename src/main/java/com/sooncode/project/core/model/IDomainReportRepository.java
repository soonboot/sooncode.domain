package com.sooncode.project.core.model;

import java.util.List;

/**
 * 领域报告存储库接口
 * @param <T>
 */
public interface IDomainReportRepository<T> {
    void add(T entity);
    void modify(T entity);
    void delete(T entity);
    boolean clear();
    List<T> getAll();
}
