package com.sooncode.project.core.model;

import com.sooncode.project.core.annotations.IgnoreField;
import com.sooncode.project.core.annotations.NotRequired;
import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.utils.BaseTypeConvert;
import com.sooncode.project.core.utils.ReflectUtils;
import java.beans.PropertyDescriptor;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;


/**
 * 领域事件基类
 */
public abstract class DomainEvent implements Serializable {
    private String id;
    private Map<String,Object> dynamicParams=new HashMap<>();
    private final Map<String,PropertyDescriptor> properties =new HashMap<>();
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
        Field field=null;
        try {
            field=this.getClass().getDeclaredField(fieldName);
        }catch (Exception e){};
        if(field!=null){
            if(field.isAnnotationPresent(IgnoreField.class)) {
                map.remove(fieldName);
                return true;
            }
            if(field.isAnnotationPresent(NotRequired.class))
                return true;
        }

        if (!map.containsKey(fieldName)) {
            throw new DomainException("缺少参数：" + fieldName);
        }
        return true;
    }
    void convertParam(Entity obj){
        PropertyDescriptor[] properties= ReflectUtils.getBeanGetters(obj.getClass());
        Map<String,Object> map=new HashMap<>();
        for(PropertyDescriptor property:properties){
            try {
                map.put(property.getName(),property.getReadMethod().invoke(obj));
            }catch (Exception ex){}
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
        Class propertyType=property.getPropertyType();
        try{
            if(property.getWriteMethod()==null||value==null)return;
            if(propertyType.isAssignableFrom(value.getClass())){
                property.getWriteMethod().invoke(en,value);
                return;
            }
            if(ValueObject.class.isAssignableFrom(propertyType)) {
                Class[] param={value.getClass()};
                Constructor constructor = ReflectUtils.getConstructor(propertyType,param);
                value= constructor.newInstance(value);
            }
            if(value==null)return;
            if(propertyType.isAssignableFrom(value.getClass())){
                property.getWriteMethod().invoke(en,value);
            }
            else if (value!=null&&value.toString().trim().length()>0){
                property.getWriteMethod().invoke(en, BaseTypeConvert.ConverTo(value.toString(), propertyType));
            }
        }catch (Exception ex){
            ex.printStackTrace();
            throw new DomainException("对象转换失败："+value+" to "+property.getName());
        }
    }
    public Object get(String fieldName){
        Object value=null;
        if(properties.containsKey(fieldName)){
            try {
                value= properties.get(fieldName).getReadMethod().invoke(this);
            }catch (Exception e){}
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
        return dynamicParams;
    }

    public void setDynamicParams(HashMap<String, Object> dynamicParams) {
        this.dynamicParams = dynamicParams;
    }
}
