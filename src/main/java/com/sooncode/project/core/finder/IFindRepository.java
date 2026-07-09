package com.sooncode.project.core.finder;

import java.util.List;
import java.util.Map;

public interface IFindRepository<T> {
    T first(FindHelper findHelper,Sort sort);
    List<T> list(FindHelper findHelper,Sort sort);
    List<T> top(FindHelper findHelper,int num,Sort sort);
    Page<T> page(FindHelper findHelper, int pageSize, int pageIndex, Sort sort);
    long count(FindHelper findHelper);
    Map<String,Long> count(FindHelper findHelper,String[] groupField);
    Map<String,T> map(FindHelper findHelper,String fieldKey);
    Map<String,Object> sum(FindHelper findHelper,String[] field,String[] groupField);
    Map<String,Object> avg(FindHelper findHelper,String[] field,String[] groupField);
    Map<String,Object> max(FindHelper findHelper,String[] field,String[] groupField);
    Map<String,Object> min(FindHelper findHelper,String[] field,String[] groupField);
    <TResult> List<TResult> distinct(FindHelper findHelper, String field,Class<TResult> cla);
}
