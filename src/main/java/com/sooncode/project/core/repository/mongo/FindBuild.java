package com.sooncode.project.core.repository.mongo;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.sooncode.project.core.finder.FindHelper;
import com.sooncode.project.core.finder.OType;
import com.sooncode.project.core.finder.Sort;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.ValueObject;
import com.sooncode.project.core.utils.ReflectUtils;

import java.beans.PropertyDescriptor;
import java.util.*;

public class FindBuild {
    private FindHelper findHelper;
    private String prefix="";
    private Class tClass;
    private final HashMap<String,PropertyDescriptor> properties;
    private FindBuild(){
        properties=new HashMap<>();
    }
    public static BasicDBObject build(Class tClass,FindHelper findHelper,String prefix){
        FindBuild build=new FindBuild();
        build.findHelper=findHelper;
        build.prefix=prefix;
        build.tClass=tClass;
        return build.build();
    }
    public static LinkedHashMap<String,IMongoDBDao.SortEnum> sort(Sort sort,String prefix){
        if(sort==null)return null;
        LinkedHashMap<String,Sort.Type> hashSort=sort.get();
        LinkedHashMap<String,IMongoDBDao.SortEnum> hashMap=new LinkedHashMap<>();
        for(Map.Entry<String,Sort.Type> en:hashSort.entrySet()){
            hashMap.put(prefix+en.getKey(),en.getValue()== Sort.Type.asc? IMongoDBDao.SortEnum.ASC: IMongoDBDao.SortEnum.DESC);
        }
        return hashMap;
    }
    public BasicDBObject build(){
        PropertyDescriptor[] properties=ReflectUtils.getBeanGetters(tClass);
        for(PropertyDescriptor p:properties)
            this.properties.put(p.getName(),p);
        BasicDBObject bson=new BasicDBObject();
        BasicDBList andValues=build(findHelper.andList());
        BasicDBList orValues=build(findHelper.orList());
        if(!andValues.isEmpty())
            bson.put("$and",andValues);
        if(!orValues.isEmpty())
            bson.put("$or",orValues);
        return bson;
    }
    private BasicDBList build(Set<Map.Entry<String, List<FindHelper.ValueType>>> fields){
        BasicDBList values=new BasicDBList();
        for(Map.Entry<String, List<FindHelper.ValueType>> field:fields){
            List<FindHelper.ValueType> vtList=field.getValue();
            if(vtList==null||vtList.size()==0)continue;
            else if(vtList.size()==1){
                if(vtList.get(0).getType()==null)
                    values.add(new BasicDBObject(getKey(field),vtList.get(0).getValue()));
                else values.add(new BasicDBObject(getKey(field),build(vtList.get(0))));
            }
            else{
                for(FindHelper.ValueType vt :vtList ){
                    if(vt.getType()==null)
                        values.add(new BasicDBObject(getKey(field),vt.getValue()));
                    else
                        values.add(new BasicDBObject(getKey(field),build(vt)));
                }
            }
        }
        return values;
    }

    private BasicDBObject build(FindHelper.ValueType vt){
        BasicDBObject bson=new BasicDBObject();
        if(vt.getType()==OType.contains)
            bson.put(O(vt.getType()),"^.*"+vt.getValue()+".*$");
        else
            bson.put(O(vt.getType()),vt.getValue());
        return bson;
    }
    private String O(OType type){
        switch (type){
            case eq:
                return "$eq";
            case neq:
                return "$ne";
            case gt:
                return "$gt";
            case gte:
                return "$gte";
            case lt:
                return "$lt";
            case lte:
                return "$lte";
            case in:
                return "$in";
            case nin:
                return "$nin";
            case contains:
                return "$regex";
            default:
                return "eq";
        }
    }
    private String getKey(Map.Entry<String, List<FindHelper.ValueType>> field){
        String key=field.getKey().split("\\.")[0];
        if(!properties.containsKey(key)) throw new DomainException("没有找到对应的字段名："+key);
        else return this.prefix+field.getKey();
    }
}
