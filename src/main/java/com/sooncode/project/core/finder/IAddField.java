package com.sooncode.project.core.finder;

import java.util.Map;

interface IAddField<T> {
    IFindWrapper<T> add(String name,Object value);
    IFindWrapper<T> add(String name,Object value,OType oType);
    IFindWrapper<T> add(Map<String,Object> map);
}
