package com.sooncode.project.core.utils;
import org.apache.commons.lang3.time.DateUtils;

import javax.swing.text.DateFormatter;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DateFormat;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.sooncode.project.core.utils.DatePattern.PARSE_PATTERNS;

public class BaseTypeConvert {
    public static Object ConverTo(String o,Class cla) throws ParseException {
        if (String.class.equals(cla)) {
            return o;
        }else if(Integer.class.equals(cla)||int.class.equals(cla)){
            return Integer.parseInt(o);
        }
        else if(Long.class.equals(cla)||long.class.equals(cla)){
            return Long.parseLong(o);
        }
        else if(Float.class.equals(cla)||float.class.equals(cla)){
            return Float.parseFloat(o);
        }
        else if(Double.class.equals(cla)||double.class.equals(cla)){
            return Double.parseDouble(o);
        }
        else if(Boolean.class.equals(cla)||boolean.class.equals(cla)){
            return Boolean.parseBoolean(o);
        }
        else if(Date.class.equals(cla)){
            return DateUtils.parseDate(o, PARSE_PATTERNS);
        }
        else if(LocalDate.class.equals(cla)){
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            return LocalDate.parse(o, formatter);
        }
        else if(LocalTime.class.equals(cla)){
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
            return LocalTime.parse(o, formatter);
        }
        else if(LocalDateTime.class.equals(cla)){
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return LocalDateTime.parse(o, formatter);
        }
        else return o;
    }
    public static Object def(Class cla){
        if(defMap.containsKey(cla))return defMap.get(cla);
        return null;
    }
    private static final Map<Class,Object> defMap=new HashMap<Class,Object>(){{
        put(int.class,0);
        put(long.class,0);
        put(float.class,0);
        put(double.class,0);
        put(boolean.class,false);
        put(String.class,"");
    }};
    public static boolean isSingleValueType(Object obj){
        if (obj == null) return true; // null 视为单值

        Class<?> clazz = obj.getClass();

        // 1. 基本类型包装类
        if (clazz.isPrimitive()) return true;

        // 2. 常见单值类型
        return clazz == String.class ||
            clazz == Integer.class ||
            clazz == Long.class ||
            clazz == Double.class ||
            clazz == Float.class ||
            clazz == Boolean.class ||
            clazz == Short.class ||
            clazz == Byte.class ||
            clazz == Character.class ||
            clazz == BigDecimal.class ||
            clazz == BigInteger.class ||
            clazz == Date.class ||
            clazz == LocalDate.class ||
            clazz == LocalDateTime.class ||
            clazz == LocalTime.class ||
            clazz == Enum.class;  // 枚举也是单值
    }
}
