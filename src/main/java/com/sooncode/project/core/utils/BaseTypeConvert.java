package com.sooncode.project.core.utils;
import org.apache.commons.lang3.time.DateUtils;

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
    public static Object ConverTo(String o,Class clazz) throws ParseException {
        if (String.class.equals(clazz)) {
            return o;
        }else if(Integer.class.equals(clazz)||int.class.equals(clazz)){
            return Integer.parseInt(o);
        }
        else if(Long.class.equals(clazz)||long.class.equals(clazz)){
            return Long.parseLong(o);
        }
        else if(Float.class.equals(clazz)||float.class.equals(clazz)){
            return Float.parseFloat(o);
        }
        else if(Double.class.equals(clazz)||double.class.equals(clazz)){
            return Double.parseDouble(o);
        }
        else if(BigDecimal.class.equals(clazz)){
            return new BigDecimal(o);
        }
        else if(BigInteger.class.equals(clazz)){
            return new BigInteger(o);
        }
        else if(clazz.isEnum()){
            return convertToEnum(o,clazz);
        }
        else if(Boolean.class.equals(clazz)||boolean.class.equals(clazz)){
            return Boolean.parseBoolean(o);
        }
        else if(Date.class.equals(clazz)){
            return DateUtils.parseDate(o, PARSE_PATTERNS);
        }
        else if(LocalDate.class.equals(clazz)){
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            return LocalDate.parse(o, formatter);
        }
        else if(LocalTime.class.equals(clazz)){
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
            return LocalTime.parse(o, formatter);
        }
        else if(LocalDateTime.class.equals(clazz)){
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return LocalDateTime.parse(o, formatter);
        }
        else return o;
    }
    public static Object def(Class clazz){
        if(defMap.containsKey(clazz)) return defMap.get(clazz);
        if(clazz != null && clazz.isEnum()) return null;
        return null;
    }
    public static Object convertToEnum(String o, Class clazz) {
        if (o == null || o.trim().isEmpty()) {
            return null;
        }
        if (!clazz.isEnum()) {
            throw new IllegalArgumentException(clazz.getName() + " is not an enum type");
        }
        String value = o.trim();
        // 方式1：直接按枚举常量名匹配（大小写敏感）
        try {
            return Enum.valueOf(clazz, value);
        } catch (IllegalArgumentException ignored) {}
        // 方式2：忽略大小写匹配
        Object[] constants = clazz.getEnumConstants();
        for (Object constant : constants) {
            if (((Enum<?>) constant).name().equalsIgnoreCase(value)) {
                return constant;
            }
        }
        // 方式3：如果输入是数字，按 ordinal 匹配
        if (value.matches("\\d+")) {
            int index = Integer.parseInt(value);
            if (index >= 0 && index < constants.length) {
                return constants[index];
            }
        }
        throw new IllegalArgumentException(
            "Cannot convert '" + value + "' to enum " + clazz.getSimpleName()
        );
    }
    private static final Map<Class,Object> defMap=new HashMap<Class,Object>(){{
        put(int.class,0);
        put(long.class,0);
        put(float.class,0);
        put(double.class,0);
        put(boolean.class,false);
        put(Date.class,new Date());
        put(LocalDate.class,LocalDate.now());
        put(LocalTime.class,LocalTime.now());
        put(LocalDateTime.class,LocalDateTime.now());
        put(BigDecimal.class,BigDecimal.ZERO);
        put(BigInteger.class,BigInteger.ZERO);
        put(String.class,"");
    }};
    public static boolean isSingleValueType(Object obj){
        if (obj == null) return true; // null 视为单值

        Class<?> clazz = obj.getClass();

        // 1. 基本类型
        if (clazz.isPrimitive()) return true;

        // 2. 常见单值类型
        return clazz == String.class
            || clazz == Integer.class
            || clazz == Long.class
            || clazz == Double.class
            || clazz == Float.class
            || clazz == Boolean.class
            || clazz == Short.class
            || clazz == Byte.class
            || clazz == Character.class
            || clazz == BigDecimal.class
            || clazz == BigInteger.class
            || clazz == Date.class
            || clazz == LocalDate.class
            || clazz == LocalDateTime.class
            || clazz == LocalTime.class
            || clazz.isEnum();
    }
}
