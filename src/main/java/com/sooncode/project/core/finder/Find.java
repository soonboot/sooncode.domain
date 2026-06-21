package com.sooncode.project.core.finder;

import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.IDomainRepository;
import com.sooncode.project.core.model.ValueObject;
import com.sooncode.project.core.monitor.Monitor;
import com.sooncode.project.core.utils.ReflectUtils;

import java.beans.PropertyDescriptor;
import java.util.HashMap;
import java.util.Map;

class Find<T  extends DomainModel<T>> implements IFind<T>{
    private IDomainRepository<T> repository;
    private IAddField<T> addField;
    private Class<T> cla;
    Find(IAddField<T> addField,Class<T> cla){
        this.cla=cla;
        this.repository= Monitor.instance.getDomainRepository();
        this.addField=addField;
    }
    private Find(){}
    @Override
    public T byId(String id){
        return repository.findByID(id,cla);
    }
    @Override
    public IFindWrapper<T> byField(String name, Object value) {
        return addField.add(name,value);
    }

    @Override
    public IFindWrapper<T> byField(String name, Object value, OType type) {
        return addField.add(name,value,type);
    }

    @Override
    public IFindWrapper<T> byMap(Map<String, Object> map) {
        return addField.add(map);
    }

    @Override
    public IFindWrapper<T> byModel(DomainModel<T> model) {
        if (model == null) return addField.add(null);
        PropertyDescriptor[] properties=ReflectUtils.getBeanGetters(model.getClass());
        HashMap map=new HashMap();
        try {
            for (PropertyDescriptor p : properties) {
                Object o = p.getReadMethod().invoke(model);
                if (o==null) continue;
                if (ValueObject.class.isAssignableFrom(p.getPropertyType())) {
                    map.put(p.getName(), ((ValueObject)o).getValue());
                }
                else map.put(p.getName(),o);
            }
        }catch (Exception ex){ex.printStackTrace();}
        return addField.add(map);
    }

}
