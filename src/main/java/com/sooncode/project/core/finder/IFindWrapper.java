package com.sooncode.project.core.finder;
import java.util.List;
import java.util.Map;

public interface IFindWrapper<T> extends IFindAction<T>{
    IFindWrapper<T> and(String name, Object value,OType oType);
    IFindWrapper<T> and(String name, Object value);
    IFindWrapper<T> and(Map<String,Object> map);
    IFindWrapper<T> or(String name, Object value,OType oType);
    IFindWrapper<T> or(String name, Object value);
    IFindWrapper<T> or(Map<String,Object> map);
    IFindWrapper<T> byField(String name, Object value);
    IFindWrapper<T> byField(String name, Object value,OType type);
    IFindWrapper<T> byMap(Map<String,Object> map);
}
