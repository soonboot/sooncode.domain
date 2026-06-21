package com.sooncode.project.core.finder;
import com.sooncode.project.core.model.DomainModel;

import java.util.Map;

public interface IFind<T>{
     IFindWrapper<T> byField(String name, Object value);
     IFindWrapper<T> byField(String name, Object value,OType type);
     IFindWrapper<T> byMap(Map<String,Object> map);
     IFindWrapper<T> byModel(DomainModel<T> model);
     T byId(String id);
}
