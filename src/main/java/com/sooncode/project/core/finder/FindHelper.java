package com.sooncode.project.core.finder;

import com.sooncode.project.core.utils.BaseTypeConvert;
import org.apache.commons.lang3.StringUtils;

import java.beans.PropertyDescriptor;
import java.util.*;

public class FindHelper{
    private HashMap<String, List<ValueType>> andMap;
    private HashMap<String, List<ValueType>> orMap;
    private String[] group;
    private boolean removeIsDefault;
    /**
     * 新条件树入口（andGroup/orGroup 使用）。
     * 与 andMap/orMap 并存：旧 API 写 andMap/orMap，新 API 写 rootCondition。
     * FindBuild 在 build() 时把两者翻译结果合并。
     */
    private ConditionNode.AndNode rootCondition;
    public FindHelper(boolean removeIsDefault){
        andMap =new HashMap<>();
        orMap=new HashMap<>();
        this.removeIsDefault=removeIsDefault;
        this.rootCondition = new ConditionNode.AndNode();
    }
    public void putAnd(String key, Object value, OType type){
        if(removeIsDefault&&eqDefault(value)) return;
        List<ValueType> list;
        if(andMap.containsKey(key))
            list= andMap.get(key);
        else {
            list = new ArrayList<>();
            this.andMap.put(key,list);
        }
        if(value.equals("false")||value.equals("true")){
            value= Boolean.parseBoolean((String) value);
        }
        else if(type==null&&value!=null&&value instanceof String){
            type=OType.contains;
        }
        ValueType vt=new ValueType(value,type);
        list.add(vt);
    }
    public void putAnd(Map<String,Object> map){
        if(map==null||map.isEmpty()) return;
        for(Map.Entry<String,Object> m:map.entrySet()){
            putAnd(m.getKey(),m.getValue(),null);
        }
    }
    public void putAnd(String key, Object value){
        putAnd(key,value,null);
    }
    public void putOr(String key, Object value, OType type){
        if(removeIsDefault&&eqDefault(value)) return;
        List<ValueType> list;
        if(orMap.containsKey(key))
            list= orMap.get(key);
        else {
            list = new ArrayList<>();
            this.orMap.put(key,list);
        }
        if(value.equals("false")||value.equals("true")){
            value= Boolean.parseBoolean((String) value);
        }
        else if(type==null&&value!=null&&value instanceof String){
            type=OType.contains;
        }
        ValueType vt=new ValueType(value,type);
        list.add(vt);
    }
    public void putOr(Map<String,Object> map){
        if(map==null||map.isEmpty()) return;
        for(Map.Entry<String,Object> m:map.entrySet()){
            putOr(m.getKey(),m.getValue(),null);
        }
    }
    public void putOr(String key, Object value){
        putOr(key,value,null);
    }
    public void setGroup(String[] group){
        this.group=group;
    }
    public String[] getGroup(){
        return group;
    }
    public Set<Map.Entry<String, List<ValueType>>> andList(){
        return andMap.entrySet();
    }
    public Set<Map.Entry<String, List<ValueType>>> orList(){
        return orMap.entrySet();
    }

    /**
     * 把已构建好的条件节点追加到 rootCondition。
     * 仅 {@link com.sooncode.project.core.repository.mongo.FindBuild#translateField}、
     * FindWrapper.andGroup/orGroup 内部使用。
     */
    public void addRootNode(ConditionNode node) {
        if (node != null) {
            this.rootCondition.add(node);
        }
    }

    /**
     * 取出 rootCondition（FindBuild 翻译用）。
     */
    public ConditionNode getRootCondition() {
        return rootCondition;
    }

    public static class ValueType{
        private Object value;
        private OType type;
        public ValueType(Object value,OType type){
            this.value=value;
            this.type=type;
        }

        public Object getValue() {
            return value;
        }
        public OType getType() {
            return type;
        }
    }
    private boolean eqDefault(Object o){
        if(o==null)return true;
        if(o.getClass().equals(String.class)&&o.toString().equals(""))
            return true;
        else if(o.getClass().equals(Integer.class)&&Integer.parseInt(o.toString())==0)
            return true;
        else if(o.getClass().equals(Long.class)&&Long.parseLong(o.toString())==0)
            return true;
        else if(o.getClass().equals(Float.class)&&Float.parseFloat(o.toString())==0.0)
            return true;
        else if(o.getClass().equals(Double.class)&&Double.parseDouble(o.toString())==0.0)
            return true;
        return false;
    }
}
