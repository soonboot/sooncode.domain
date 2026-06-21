package com.sooncode.project.core.generic;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.parser.DefaultJSONParser;
import com.alibaba.fastjson.parser.deserializer.ObjectDeserializer;
import com.sooncode.project.core.model.Entity;

import java.lang.reflect.Type;

/**
 * 类名: ReplayEventDeserialize
 * 说明: TODO
 * 创建日期: 2021-10-08 17:43
 * 创建人: 赵金歌
 **/
public class ReplayEventDeserialize implements ObjectDeserializer {
    @Override
    public Entity deserialze(DefaultJSONParser parser, Type type, Object o) {
        Class clz= null;
        try {
            clz = Class.forName(JSON.parseObject(parser.getInput()).getString("wclass"));
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        Entity data=(Entity)parser.parseObject(clz);
        return data;
    }

    @Override
    public int getFastMatchToken() {
        return 0;
    }
}
