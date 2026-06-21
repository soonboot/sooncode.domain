package com.sooncode.project.core.finder;


import java.util.HashMap;
import java.util.LinkedHashMap;

public class Sort {
    private LinkedHashMap<String,Type> sort;
    public static Sort ASC(String name){
        return new Sort(name,Type.asc);
    }
    public static Sort DESC(String name){
        return new Sort(name,Type.desc);
    }
    public Sort Asc(String name){
        return add(name,Type.asc);
    }
    public Sort Desc(String name){
        return add(name,Type.desc);
    }
    public Sort(String name, Type type){
        sort=new LinkedHashMap<>();
        sort.put(name,type);
    }
    public Sort add(String name,Type type){
        sort.put(name,type);
        return this;
    }
    public LinkedHashMap<String,Type> get(){
        return sort;
    }
    public enum Type{
        desc,
        asc
    }
}
