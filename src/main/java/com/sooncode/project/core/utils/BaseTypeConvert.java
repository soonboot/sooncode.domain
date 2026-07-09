package com.sooncode.project.core.utils;
import org.apache.commons.lang3.time.DateUtils;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.sooncode.project.core.utils.DatePattern.PARSE_PATTERNS;

public class BaseTypeConvert {

    /**
     * 旧 API 入口：仅支持 String → 目标类型。委托给 {@link #convertValue(Object, Class)}。
     */
    public static Object ConverTo(String o, Class clazz) throws ParseException {
        return convertValue(o, clazz);
    }

    /**
     * 通用类型转换：支持 String、Date、LocalDateTime、LocalDate、LocalTime、Boolean、Number 等
     * 常见值类型之间互转。
     */
    public static Object convertValue(Object value, Class<?> targetType) throws ParseException {
        if (value == null) {
            return null;
        }
        // 同类型直接返回
        if (targetType.isInstance(value)) {
            return value;
        }
        // 目标为 String
        if (targetType == String.class) {
            if (value instanceof Date) {
                return new SimpleDateFormat(DatePattern.DATETIME).format((Date) value);
            }
            return value.toString();
        }
        // 目标为基础类型 / 包装类 / BigDecimal / BigInteger / Enum / Boolean / Date / Local* —— 全部就地展开解析
        String s = value.toString();
        if (targetType == Integer.class || targetType == int.class) {
            return Integer.parseInt(s);
        }
        if (targetType == Long.class || targetType == long.class) {
            return Long.parseLong(s);
        }
        if (targetType == Float.class || targetType == float.class) {
            return Float.parseFloat(s);
        }
        if (targetType == Double.class || targetType == double.class) {
            return Double.parseDouble(s);
        }
        if (targetType == BigDecimal.class) {
            return new BigDecimal(s);
        }
        if (targetType == BigInteger.class) {
            return new BigInteger(s);
        }
        if (targetType.isEnum()) {
            return convertToEnum(s, targetType);
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            return Boolean.parseBoolean(s);
        }
        if (targetType == Date.class) {
            return DateUtils.parseDate(s, PARSE_PATTERNS);
        }
        if (targetType == LocalDate.class) {
            return LocalDate.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        if (targetType == LocalTime.class) {
            return LocalTime.parse(s, DateTimeFormatter.ofPattern("HH:mm:ss"));
        }
        if (targetType == LocalDateTime.class) {
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        // 兜底：未知类型原样返回
        return value;
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
