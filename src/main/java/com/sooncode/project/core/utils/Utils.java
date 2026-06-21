package com.sooncode.project.core.utils;
import com.mongodb.BasicDBObject;
import com.mongodb.client.model.Filters;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Utils {
    public static MapBuilder mapBuilder(String key,Object value){
        return new MapBuilder(key,value);
    }
    public static class MapBuilder extends HashMap{
        public MapBuilder(String key,Object value){
            this.put(key,value);
        }
        public MapBuilder add(String key,Object value){
            this.put(key,value);
            return this;
        }
    }
    public static BsonBuilder bsonBuilder(String key,Object value){
        return new BsonBuilder(key,value);
    }
    public static BsonBuilder bsonBuilder(){
        return new BsonBuilder();
    }
    public static BsonBuilder bsonBuilder(Map<String,Object> map){
        return new BsonBuilder(map);
    }
    public static class BsonBuilder extends BasicDBObject{
        List<Bson> list=new ArrayList<>();
        List<List<Bson>> blist=new ArrayList<>();
        public BsonBuilder(String key, Object value){
            this.put(key,value);
            list.add(Filters.eq(key,value));
        }
        public BsonBuilder(Map<String,Object> map){
            for(Map.Entry entry:map.entrySet()){
                String key=entry.getKey().toString();
                Object value=entry.getValue();
                this.put(key,value);
                list.add(Filters.eq(key,value));
            }
        }
        public BsonBuilder(){};
        public BsonBuilder add(String key,Object value){
            this.put(key,value);
            list.add(Filters.eq(key,value));
            return this;
        }
        public BsonBuilder or() {
            blist.add(list);
            this.put("$or",blist.get(blist.size()-1));
            list=new ArrayList<>();
            return this;
        }
        public BsonBuilder and(){
            blist.add(list);
            this.put("$and",blist.get(blist.size()-1));
            list=new ArrayList<>();
            return this;
        }
        public BsonBuilder eq(String key,Object value){
            list.add(Filters.eq(key,value));
            return this;
        }
        public BsonBuilder in(String key,Object value){
            list.add(Filters.in(key,value));
            return this;
        }
    }
    public class KeyValuePair <K, V>
    {
        public KeyValuePair(K key, V value)
        {
            this.key = key;
            this.value = value;
        }

        private K key;
        public void setKey(K key) {
            this.key = key;
        }
        public K getKey() {
            return key;
        }

        private V value;
        public void setValue(V value) {
            this.value = value;
        }
        public V getValue() {
            return value;
        }

        public boolean equals(KeyValuePair<K, V> pair)
        {
            return pair.getKey() == this.key && pair.getValue() == this.value;
        }
    }
}
