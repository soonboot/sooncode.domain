package com.sooncode.project.core.repository.mongo;


import com.sooncode.project.core.utils.EntityConvert;

import java.util.Map;

public class MongoJsonUtil {
    public static Map<String, Object> toJsonObject(Object object)
    {
        if(object == null){
            return null;
        }
        Map<String, Object> result = EntityConvert.entityToMap(object);
        return result;
    }
}
