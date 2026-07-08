package com.sooncode.project.core.utils;

import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.SimpleObject;
import com.sooncode.project.core.model.ValueObject;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EntityConvert{
    public static void mapToEntity(Map<String,Object> map, Object target){
        for(PropertyDescriptor property: ReflectUtils.getBeanSetters(target.getClass())) {
            if(map.containsKey(property.getName())){
                try{
                    Class propertyClazz=property.getPropertyType();
                    Object obj=map.get(property.getName());
                    if(obj==null)continue;
                    if(propertyClazz.isAssignableFrom(obj.getClass())){
                        property.getWriteMethod().invoke(target,obj);
                        continue;
                    }
                    if(obj==null)continue;
                    if(propertyClazz.isAssignableFrom(obj.getClass())){
                        property.getWriteMethod().invoke(target,obj);
                    }
                    else if (obj!=null&&obj.toString().trim().length()>0){
                        property.getWriteMethod().invoke(target, BaseTypeConvert.ConverTo(obj.toString(), propertyClazz));
                    }
                }
                catch (Exception e){
                    e.printStackTrace();
                    throw new DomainException("对象转换失败："+property.getName());
                }
            }
        }
    }
    public static Map<String,Object> entityToMap(Object sourctObj){
        if(sourctObj==null) return null;
        PropertyDescriptor[] properties = ReflectUtils.getBeanGetters(sourctObj.getClass());
        Map<String,Object> map=new HashMap<>();
        for (PropertyDescriptor property : properties) {
            try {
                Object o=property.getReadMethod().invoke(sourctObj);
                if(o==null){
                    map.put(property.getName(),null);
                    continue;
                }
                else if(Map.class.isAssignableFrom(o.getClass())){
                    Map<String,Object> objMap=(Map<String,Object>)o;
                    for(Map.Entry<String,Object> entry : objMap.entrySet()){
                        Object value=entry.getValue();
                        if(value==null) continue;
                        else if(ValueObject.class.isAssignableFrom(value.getClass())
                            || SimpleObject.class.isAssignableFrom(value.getClass())
                            || DomainModel.class.isAssignableFrom(value.getClass())){
                            value = entityToMap(value);
                            objMap.put(entry.getKey(),value);
                        }
                    }
                }
                else if(List.class.isAssignableFrom(o.getClass())){
                    List<Object> list=(List<Object>)o;
                    for(Object value : (List<Object>)o){
                        if(value==null)
                            list.add(null);
                        else if(ValueObject.class.isAssignableFrom(value.getClass())
                            || SimpleObject.class.isAssignableFrom(value.getClass())
                            || DomainModel.class.isAssignableFrom(value.getClass())){
                            value = entityToMap(value);
                            list.add(value);
                        }
                    }
                }
                else if(ValueObject.class.isAssignableFrom(o.getClass())||SimpleObject.class.isAssignableFrom(o.getClass())|| DomainModel.class.isAssignableFrom(o.getClass())){
                    o=entityToMap(o);
                }
                map.put(property.getName(),o);
            }catch(java.lang.IllegalAccessException|java.lang.reflect.InvocationTargetException e){
                e.printStackTrace();
                throw new DomainException("读取失败，找到同名属性："+property.getName());
            }catch (Exception ex){
                ex.printStackTrace();
                throw new DomainException("数据转换失败："+property.getName());
            }

        }
        return map;
    }
    public static void copyPropertys(Object sourceObj,Object targetObj){
        PropertyDescriptor[] targetPropertys = ReflectUtils.getBeanSetters(targetObj.getClass());
        PropertyDescriptor[] sourcePropertys= ReflectUtils.getBeanGetters(sourceObj.getClass());
        for (PropertyDescriptor sourceProperty : sourcePropertys) {
            try {
                for (PropertyDescriptor targetPropterty : targetPropertys) {
                    if(targetPropterty.getName().equals(sourceProperty.getName())){
                        Object o=sourceProperty.getReadMethod().invoke(sourceObj);
                        targetPropterty.getWriteMethod().invoke(targetObj,o);
                        break;
                    }

                }
            }catch (java.lang.IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
                e.printStackTrace();
                throw new DomainException("读取或写入失败，找到同名属性：" + sourceProperty.getName());
            }

        }
    }
    public static void EntityToEntity(Object SourceObj,Object targetObj){
        PropertyDescriptor[] targetPropertys = ReflectUtils.getBeanSetters(targetObj.getClass());
        PropertyDescriptor[] thisPropertys= ReflectUtils.getBeanGetters(SourceObj.getClass());
        for (PropertyDescriptor thisProperty : thisPropertys) {
            try {
                for (PropertyDescriptor targetPropterty : targetPropertys) {
                    if (targetPropterty.getName().equals(thisProperty.getName())) {
                        if (targetPropterty.getWriteMethod() == null) break;
                        try {
                            Object o = thisProperty.getReadMethod().invoke(SourceObj);
                            if(o==null)break;
                            if(o.toString().trim().length()<=0)break;
                            if(targetPropterty.getPropertyType().isAssignableFrom(o.getClass())){
                                targetPropterty.getWriteMethod().invoke(targetObj,o);
                            }
                            else
                                targetPropterty.getWriteMethod().invoke(targetObj, BaseTypeConvert.ConverTo(o.toString(),targetPropterty.getPropertyType()));
                        } catch (java.lang.IllegalAccessException | java.lang.reflect.InvocationTargetException e) {
                            e.printStackTrace();
                            throw new DomainException("读取或写入失败，找到同名属性：" + thisProperty.getName());
                        }
                        break;
                    }
                }
            }catch (Exception e){
                e.printStackTrace();
                throw new DomainException("数据转换失败："+thisProperty.getName());
            }
        }
    }

}
