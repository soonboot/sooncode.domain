package com.sooncode.project.core.model;

import com.sooncode.project.core.annotations.IgnoreField;
import com.sooncode.project.core.annotations.NotRequired;
import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.utils.BaseTypeConvert;
import com.sooncode.project.core.utils.ReflectUtils;
import java.beans.PropertyDescriptor;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * 领域事件基类
 */
public abstract class DomainEvent implements Serializable {
    private String id;
    private Map<String,Object> dynamicParams=new LinkedHashMap<>();
    private final Map<String,PropertyDescriptor> properties =new LinkedHashMap<>();
    protected DomainEvent(){
        getFields();
    }
    public DomainEvent(String  aggregateId)
    {
        getFields();
        setId(aggregateId);
    }
    public DomainEvent(String aggregateId,Entity obj){
        this(obj);
        setId(aggregateId);
    }
    public DomainEvent(String aggregateId,Map<String, Object> map){
        this(map);
        setId(aggregateId);
    }
    public DomainEvent(Entity obj){
        getFields();
        convertParam(obj);
    }
    public DomainEvent(Map<String,Object> map){
        getFields();
        convertParam(map);
    }
    private void getFields(){
        PropertyDescriptor[] propertyDescriptors=ReflectUtils.getBeanProperties(this.getClass());
        for(PropertyDescriptor property:propertyDescriptors){
            if(property.getName().equals("dynamicParams")) continue;
            if(property.getName().equals("id"))continue;
            properties.put(property.getName(),property);
        }
    }

    private boolean checkParam(String fieldName,Map map){
        Field field;
        try {
            field=this.getClass().getDeclaredField(fieldName);
        }catch (NoSuchFieldException e){
            throw new DomainException("字段不存在："+fieldName+" on "+this.getClass().getName());
        }
        if(field.isAnnotationPresent(IgnoreField.class)) {
            map.remove(fieldName);
            return true;
        }
        if(field.isAnnotationPresent(NotRequired.class))
            return true;
        if (!map.containsKey(fieldName)) {
            throw new DomainException("缺少参数：" + fieldName);
        }
        return true;
    }
    void convertParam(Entity obj){
        PropertyDescriptor[] properties= ReflectUtils.getBeanGetters(obj.getClass());
        Map<String,Object> map=new LinkedHashMap<>();
        for(PropertyDescriptor property:properties){
            try {
                map.put(property.getName(),property.getReadMethod().invoke(obj));
            }catch (Exception ex){
                DomainException de = new DomainException("读取实体字段失败："+property.getName());
                de.initCause(ex);
                throw de;
            }
        }
        convertParam(map);
    }
    void convertParam(Map<String, Object> map){
        if(this.getClass().isAnnotationPresent(EventBoot.class)){
            String[] params=this.getClass().getAnnotation(EventBoot.class).Params();
            for(String p:params){
                checkParam(p,map);
            }
        }
        for(PropertyDescriptor property: properties.values()) {
            checkParam(property.getName(),map);
        }
        for(Map.Entry mapEn:map.entrySet()){
            set(mapEn.getKey().toString(), mapEn.getValue());
        }
    }
    public void projectiveEntity(DomainModel en){
        PropertyDescriptor[] propertys= ReflectUtils.getBeanProperties(en.getClass());
        for(PropertyDescriptor property:propertys){
            if(property.getName().equals("id")) continue;
            if(this.hasField(property.getName())&&property.getWriteMethod()!=null) {
                Object value=this.get(property.getName());
                setPropertyValue(en,property,value);
            }
        }
    }
    public void set(String fieldName,Object value){
        if(!properties.containsKey(fieldName)) {
            if(!this.getClass().isAnnotationPresent(EventBoot.class))return;
            EventBoot eb= this.getClass().getAnnotation(EventBoot.class);
            String[] ps=eb.Params();
            for(String s:ps){
                if(s.equals(fieldName)){
                    this.dynamicParams.put(fieldName, value);
                    return;
                }
            }
            if(eb.KeepAll())
                this.dynamicParams.put(fieldName,value);
        }
        else{
            if(value==null)return;
            PropertyDescriptor property=properties.get(fieldName);
            setPropertyValue(this,property,value);
        }
    }
    private void setPropertyValue(Object en,PropertyDescriptor property,Object value){
        Class<?> propertyType=property.getPropertyType();
        try{
            if(property.getWriteMethod()==null||value==null)return;
            if(propertyType.isAssignableFrom(value.getClass())){
                property.getWriteMethod().invoke(en,value);
            }
            else if (value.toString().trim().length()>0){
                property.getWriteMethod().invoke(en, BaseTypeConvert.ConverTo(value.toString(), propertyType));
            }
        }catch (Exception ex){
            DomainException de = new DomainException("对象转换失败："+value+" to "+property.getName());
            de.initCause(ex);
            throw de;
        }
    }
    public Object get(String fieldName){
        Object value=null;
        if(properties.containsKey(fieldName)){
            try {
                value= properties.get(fieldName).getReadMethod().invoke(this);
            }catch (Exception e){
                DomainException de = new DomainException("读取事件字段失败："+fieldName);
                de.initCause(e);
                throw de;
            }
        }
        else {
            value=this.dynamicParams.get(fieldName);
        }
        return value;
    }
    public boolean hasField(String fieldName){
        return properties.containsKey(fieldName)||dynamicParams.containsKey(fieldName);
    }
    public String getId() {
        return id;
    }

    void setId(String id) {
        this.id = id;
    }
    public Map<String, Object> getDynamicParams() {
        return Collections.unmodifiableMap(dynamicParams);
    }

    public void setDynamicParams(Map<String, Object> dynamicParams) {
        this.dynamicParams = dynamicParams == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(dynamicParams);
    }
}
