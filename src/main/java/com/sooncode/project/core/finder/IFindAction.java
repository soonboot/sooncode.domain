package com.sooncode.project.core.finder;

import java.util.List;
import java.util.Map;

public interface IFindAction<T> {
    T first(Sort sort);
    T first();
    List<T> list(Sort sort);
    List<T> list();
    Map<String,T> map(String fieldKey);
    List<T> top(int num,Sort sort);
    List<T> top(int num);

    /**
     *
     * @param pageSize 分页大小
     * @param pageIndex 页码
     * @param sort 排序
     * @return
     */
    Page<T> page(int pageSize,int pageIndex,Sort sort);

    /**
     *
     * @param pageSize 分页大小
     * @param pageIndex 页码
     * @return
     */
    Page<T> page(int pageSize,int pageIndex);
    long count();
    Map<String,Long> count(String[] groupField);
    <TResult> List<TResult> distinct(String field,Class<TResult> cla);
    Map<String,Object> sum(String[] field,String[] groupField);
    Map<String,Object> avg(String[] field,String[] groupField);
    Map<String,Object> max(String[] field,String[] groupField);
    Map<String,Object> min(String[] field,String[] groupField);
}
