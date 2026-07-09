package com.sooncode.project.core.utils;

import com.sooncode.project.core.annotations.IgnoreField;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.SimpleObject;
import com.sooncode.project.core.model.ValueObject;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

public class EntityConvert {

    // ----------------------- mapToEntity -----------------------

    /**
     * 将 Map 的值按目标实体的 setter 写入。
     * 支持：扁平字段、Map/List 嵌套、DomainModel/ValueObject 嵌套、null 显式回写、IgnoreField 跳过。
     */
    public static void mapToEntity(Map<String, Object> map, Object target) {
        if (map == null || target == null) {
            return;
        }
        for (PropertyDescriptor property : ReflectUtils.getBeanSetters(target.getClass())) {
            if (property.getWriteMethod() == null) {
                continue;
            }
            if (!map.containsKey(property.getName())) {
                continue;
            }
            if (isIgnoredField(target.getClass(), property.getName())) {
                continue;
            }
            Object value = map.get(property.getName());
            try {
                Object converted = convertMapValueToTarget(value, property.getPropertyType());
                property.getWriteMethod().invoke(target, converted);
            } catch (InvocationTargetException e) {
                throw wrap("对象转换失败", property.getName(), e.getTargetException());
            } catch (Exception e) {
                throw wrap("对象转换失败", property.getName(), e);
            }
        }
    }

    /**
     * 根据目标字段类型，把 map 中的原始值转换为合适类型：
     * - null → null（包含显式置空）
     * - 单值类型 → 走 BaseTypeConvert
     * - Map/List 嵌套 → 递归
     * - DomainModel/ValueObject/SimpleObject → 反射 newInstance 后递归 mapToEntity
     */
    private static Object convertMapValueToTarget(Object value, Class<?> targetType) throws Exception {
        if (value == null) {
            return null;
        }
        // 同类型直接返回
        if (targetType.isInstance(value)) {
            return value;
        }
        if (targetType == String.class) {
            return value.toString();
        }
        if (BaseTypeConvert.isSingleValueType(value) || isSingleValueTypeOf(value.getClass())) {
            return BaseTypeConvert.convertValue(value, targetType);
        }
        if (Map.class.isAssignableFrom(targetType) && value instanceof Map) {
            // 嵌套 Map 保持原状（无法推断 value 类型）；如需强类型请使用 DomainModel 字段
            return value;
        }
        if (List.class.isAssignableFrom(targetType) && value instanceof List) {
            return value;
        }
        if (isAssignableFromAny(value.getClass(), DomainModel.class, ValueObject.class, SimpleObject.class)) {
            Object nested = targetType.getDeclaredConstructor().newInstance();
            mapToEntity((Map<String, Object>) value, nested);
            return nested;
        }
        if (isAssignableFromAny(targetType, DomainModel.class, ValueObject.class, SimpleObject.class)
                && value instanceof Map) {
            Object nested = targetType.getDeclaredConstructor().newInstance();
            mapToEntity((Map<String, Object>) value, nested);
            return nested;
        }
        // 兜底：尝试用 BaseTypeConvert（如 toString 后能解析）
        return BaseTypeConvert.convertValue(value, targetType);
    }

    // ----------------------- entityToMap -----------------------

    /**
     * 将实体（含嵌套 Map/List/Set/数组/DomainModel/ValueObject）序列化为 Map。
     * 序列化结果保证可安全写入 Mongo / FastJSON。
     * 同一对象在递归路径上出现时会被降级为占位符（避免双向引用 / 自引用导致 StackOverflow）。
     */
    public static Map<String, Object> entityToMap(Object sourceObj) {
        return entityToMap(sourceObj, new IdentityHashMap<>());
    }

    private static Map<String, Object> entityToMap(Object sourceObj, IdentityHashMap<Object, Boolean> visiting) {
        if (sourceObj == null) {
            return null;
        }
        // 循环引用检测：同一对象在当前调用栈上出现 → 降级为占位
        if (visiting.put(sourceObj, Boolean.TRUE) != null) {
            Map<String, Object> cycleMarker = new LinkedHashMap<>();
            cycleMarker.put("@cycleRef", sourceObj.getClass().getName());
            return cycleMarker;
        }
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            for (PropertyDescriptor property : ReflectUtils.getBeanGetters(sourceObj.getClass())) {
                if (property.getReadMethod() == null) {
                    continue;
                }
                if (isIgnoredField(sourceObj.getClass(), property.getName())) {
                    continue;
                }
                Object value;
                try {
                    value = property.getReadMethod().invoke(sourceObj);
                } catch (InvocationTargetException e) {
                    throw wrap("读取失败", property.getName(), e.getTargetException());
                } catch (IllegalAccessException e) {
                    throw wrap("读取失败", property.getName(), e);
                }
                try {
                    map.put(property.getName(), convertValue(value, visiting));
                } catch (Exception e) {
                    throw wrap("数据转换失败", property.getName(), e);
                }
            }
            return map;
        } finally {
            visiting.remove(sourceObj);
        }
    }

    /**
     * 将任意值转换为可安全序列化的形式。
     * 单值类型 → 原样；Map → 递归；List/Set/数组 → 逐元素递归；DomainModel 等 → 递归 entityToMap。
     * 兜底：未知 POJO 递归 entityToMap。
     * 循环引用通过 visiting 集合检测并降级为 {@code @cycleRef} 占位。
     */
    private static Object convertValue(Object value, IdentityHashMap<Object, Boolean> visiting) {
        if (value == null) {
            return null;
        }
        if (BaseTypeConvert.isSingleValueType(value)) {
            return value;
        }
        if (value instanceof Map) {
            Map<?, ?> srcMap = (Map<?, ?>) value;
            Map<String, Object> newMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : srcMap.entrySet()) {
                newMap.put(String.valueOf(entry.getKey()), convertValue(entry.getValue(), visiting));
            }
            return newMap;
        }
        if (value instanceof List) {
            List<?> srcList = (List<?>) value;
            List<Object> newList = new ArrayList<>(srcList.size());
            for (Object item : srcList) {
                newList.add(convertValue(item, visiting));
            }
            return newList;
        }
        if (value instanceof Set) {
            Set<?> srcSet = (Set<?>) value;
            List<Object> newList = new ArrayList<>(srcSet.size());
            for (Object item : srcSet) {
                newList.add(convertValue(item, visiting));
            }
            return newList;
        }
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            List<Object> newList = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                newList.add(convertValue(Array.get(value, i), visiting));
            }
            return newList;
        }
        if (isAssignableFromAny(value.getClass(), DomainModel.class, ValueObject.class, SimpleObject.class)) {
            return entityToMap(value, visiting);
        }
        // 兜底：未知 POJO 尝试递归展开（避免 FastJSON 触发循环引用）
        return entityToMap(value, visiting);
    }

    // ----------------------- copyProperties -----------------------

    /**
     * 将 source 对象的属性拷贝到 target 对象。同名字段才拷贝。
     * strict=true 时过滤 null/空串，并做类型转换（Date → String 等）。
     * strict=false 时直接赋值（保留原 copyPropertys 行为）。
     */
    public static void copyProperties(Object sourceObj, Object targetObj, boolean strict) {
        if (sourceObj == null || targetObj == null) {
            return;
        }
        PropertyDescriptor[] targetProperties = ReflectUtils.getBeanSetters(targetObj.getClass());
        PropertyDescriptor[] sourceProperties = ReflectUtils.getBeanGetters(sourceObj.getClass());
        for (PropertyDescriptor sourceProperty : sourceProperties) {
            if (isIgnoredField(sourceObj.getClass(), sourceProperty.getName())) {
                continue;
            }
            try {
                for (PropertyDescriptor targetProperty : targetProperties) {
                    if (!targetProperty.getName().equals(sourceProperty.getName())) {
                        continue;
                    }
                    if (targetProperty.getWriteMethod() == null || sourceProperty.getReadMethod() == null) {
                        break;
                    }
                    Object value = sourceProperty.getReadMethod().invoke(sourceObj);
                    if (strict) {
                        if (value == null) {
                            break;
                        }
                        if (value.toString().trim().isEmpty()) {
                            break;
                        }
                    }
                    try {
                        Object converted = value == null
                                ? null
                                : BaseTypeConvert.convertValue(value, targetProperty.getPropertyType());
                        targetProperty.getWriteMethod().invoke(targetObj, converted);
                    } catch (InvocationTargetException e) {
                        throw wrap("读取或写入失败", sourceProperty.getName(), e.getTargetException());
                    } catch (IllegalAccessException e) {
                        throw wrap("读取或写入失败", sourceProperty.getName(), e);
                    } catch (Exception e) {
                        throw wrap("数据转换失败", sourceProperty.getName(), e);
                    }
                    break;
                }
            } catch (Exception e) {
                throw wrap("数据转换失败", sourceProperty.getName(), e);
            }
        }
    }

    /**
     * 保留旧 API 行为：不过滤 null/空串，直接浅拷贝。
     */
    public static void copyPropertys(Object sourceObj, Object targetObj) {
        copyProperties(sourceObj, targetObj, false);
    }

    /**
     * 保留旧 API 行为：过滤 null/空串，并做类型转换。
     * 等价于 copyProperties(src, target, true)。
     */
    public static void EntityToEntity(Object sourceObj, Object targetObj) {
        copyProperties(sourceObj, targetObj, true);
    }

    // ----------------------- 工具方法 -----------------------

    private static boolean isIgnoredField(Class<?> clazz, String fieldName) {
        try {
            java.lang.reflect.Field field = findField(clazz, fieldName);
            return field != null && field.isAnnotationPresent(IgnoreField.class);
        } catch (Exception e) {
            return false;
        }
    }

    private static java.lang.reflect.Field findField(Class<?> clazz, String fieldName) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    private static boolean isAssignableFromAny(Class<?> target, Class<?>... types) {
        for (Class<?> t : types) {
            if (t.isAssignableFrom(target)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSingleValueTypeOf(Class<?> clazz) {
        // 包装类基本类型
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

    private static DomainException wrap(String action, String field, Throwable cause) {
        String msg = action + "，字段：" + field
                + " | " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        DomainException de = new DomainException(msg);
        de.initCause(cause);
        return de;
    }
}
