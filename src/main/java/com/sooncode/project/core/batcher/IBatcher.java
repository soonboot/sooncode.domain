package com.sooncode.project.core.batcher;

import com.sooncode.project.core.model.DomainModel;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * 批量操作统一接口。批量新增、修改、删除以及领域行为收集都由该接口管理。
 *
 * @param <T> 领域模型类型
 */
public interface IBatcher<T extends DomainModel> {
    IBatcher<T> add(T entity);
    IBatcher<T> addAll(Collection<T> entities);
    IBatcher<T> save(T entity);
    IBatcher<T> saveAll(Collection<T> entities);
    IBatcher<T> delete(T entity);
    IBatcher<T> deleteAll(Collection<T> entities);
    IBatcher<T> deleteByIds(Collection<String> ids);
    IBatcher<T> options(BatchOptions options);
    BatchOptions getOptions();
    BatchResult<T> run(Consumer<? super IBatcher<T>> action);
    BatchResult<T> execute();
}
