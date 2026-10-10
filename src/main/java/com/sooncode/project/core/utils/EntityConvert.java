package com.sooncode.project.core.utils;

import com.sooncode.project.core.annotations.IgnoreField;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.SimpleObject;
import com.sooncode.project.core.model.ValueObject;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
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
            if (isIgnoredField(target.getClass(), property)) {
                continue;
            }
            Object value = map.get(property.getName());
            try {
                Object converted = convertPropertyValue(value, property);
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
    /**
     * 按 JavaBean 属性的实际类型（包括 List&lt;ValueObject&gt; 的泛型元素类型）转换值。
     * 事件参数和实体反序列化都必须经过同一套转换，否则泛型集合会被原样写成 List&lt;Map&gt;。
     */
    public static Object convertPropertyValue(Object value, PropertyDescriptor property) throws Exception {
        return convertMapValueToTarget(value, property.getPropertyType(), property.getReadMethod() == null
                ? property.getWriteMethod().getGenericParameterTypes()[0]
                : property.getReadMethod().getGenericReturnType());
    }

    private static Object convertMapValueToTarget(Object value, Class<?> targetType, Type genericType) throws Exception {
        if (value == null) {
            return null;
        }
        if (targetType == String.class) {
            return value.toString();
        }
        if (BaseTypeConvert.isSingleValueType(value) || isSingleValueTypeOf(value.getClass())) {
            return BaseTypeConvert.convertValue(value, targetType);
        }
        if (Map.class.isAssignableFrom(targetType) && value instanceof Map) {
            // Map 的 key/value 泛型通常无法安全推断，保持递归后的 Map。
            return value;
        }
        // 目标是数组（int[] / String[] / Integer[] / VO[] / ArrayList[] 等）：
        // 来源可以是 Collection（entityToMap 把数组序列化成 List，反序列化时回到数组就走这里），
        // 也可以是数组（手动构造的 Map 或 List<int[]> 这类嵌套元素）。
        if (targetType.isArray()) {
            return convertToArray(value, targetType, genericType);
        }
        // 来源是数组、目标是集合（List / Set / Queue / Collection 等）：
        // 兼容手动构造 Map / 上游直接透传数组的场景。
        if (value.getClass().isArray() && Collection.class.isAssignableFrom(targetType)) {
            return convertArrayToCollection(value, targetType, genericType);
        }
        if (List.class.isAssignableFrom(targetType) && value instanceof Collection) {
            Collection<?> source = (Collection<?>) value;
            List<Object> result = newListForTarget(targetType, source.size());
            Type elementType = collectionElementType(genericType);
            for (Object item : source) {
                result.add(convertGenericValue(item, elementType));
            }
            return result;
        }
        if (Set.class.isAssignableFrom(targetType) && value instanceof Collection) {
            Collection<?> source = (Collection<?>) value;
            Set<Object> result = newSetForTarget(targetType, source.size());
            Type elementType = collectionElementType(genericType);
            for (Object item : source) {
                result.add(convertGenericValue(item, elementType));
            }
            return result;
        }
        // 其他 Collection 实现（Queue / Deque / Collection 接口本身等）：按目标类型实例化。
        if (Collection.class.isAssignableFrom(targetType) && value instanceof Collection) {
            Collection<?> source = (Collection<?>) value;
            Collection<Object> result = newCollectionForTarget(targetType, source.size());
            Type elementType = collectionElementType(genericType);
            for (Object item : source) {
                result.add(convertGenericValue(item, elementType));
            }
            return result;
        }
        // 同类型直接返回。集合必须先经过上面的泛型元素转换。
        if (targetType.isInstance(value)) {
            return value;
        }
        if (isAssignableFromAny(value.getClass(), DomainModel.class, ValueObject.class, SimpleObject.class)) {
            if (value instanceof Map) {
                Object nested = targetType.getDeclaredConstructor().newInstance();
                mapToEntity((Map<String, Object>) value, nested);
                return nested;
            }
            // 值已经是领域对象：同层级直接复用，否则经 Map 中转做跨类型拷贝。
            if (targetType.isInstance(value)) {
                return value;
            }
            Map<String, Object> snapshot = entityToMap(value);
            Object nested = targetType.getDeclaredConstructor().newInstance();
            mapToEntity(snapshot, nested);
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

    /**
     * 将 Collection / 数组 / 单值转换为目标数组。
     * 基本类型数组（int[] / long[] 等）用 {@link Array#set} 逐元素写入，自动处理拆箱，
     * 不能做 <code>(Object[])</code> 强转。
     */
    private static Object convertToArray(Object value, Class<?> targetType, Type genericType) throws Exception {
        Class<?> componentType = targetType.getComponentType();
        Type componentGenericType;
        if (genericType instanceof GenericArrayType) {
            componentGenericType = ((GenericArrayType) genericType).getGenericComponentType();
        } else {
            componentGenericType = componentType;
        }
        List<?> source;
        if (value instanceof Collection) {
            source = new ArrayList<>((Collection<?>) value);
        } else if (value != null && value.getClass().isArray()) {
            int len = Array.getLength(value);
            List<Object> tmp = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                tmp.add(Array.get(value, i));
            }
            source = tmp;
        } else {
            // 单值包装成长度为 1 的数组（如 map 里手写 "1" 给 int[]）。
            source = Collections.singletonList(value);
        }
        Object array = Array.newInstance(componentType, source.size());
        int i = 0;
        for (Object item : source) {
            Array.set(array, i++, convertGenericValue(item, componentGenericType));
        }
        return array;
    }

    /**
     * 将数组转换为目标集合。元素同样走泛型转换，支持 {@code String[] -> List<Integer>}、
     * {@code Map[] -> List<ValueObject>} 等。
     */
    private static Object convertArrayToCollection(Object value, Class<?> targetType, Type genericType) throws Exception {
        int len = Array.getLength(value);
        Type elementType = collectionElementType(genericType);
        if (List.class.isAssignableFrom(targetType)) {
            List<Object> result = newListForTarget(targetType, len);
            for (int i = 0; i < len; i++) {
                result.add(convertGenericValue(Array.get(value, i), elementType));
            }
            return result;
        }
        if (Set.class.isAssignableFrom(targetType)) {
            Set<Object> result = newSetForTarget(targetType, len);
            for (int i = 0; i < len; i++) {
                result.add(convertGenericValue(Array.get(value, i), elementType));
            }
            return result;
        }
        Collection<Object> result = newCollectionForTarget(targetType, len);
        for (int i = 0; i < len; i++) {
            result.add(convertGenericValue(Array.get(value, i), elementType));
        }
        return result;
    }

    /**
     * 按目标 List 类型创建空集合：接口 / 抽象类回退为 ArrayList，
     * ArrayList / LinkedList 等常用实现保持原类型，其他具体类尝试反射实例化。
     */
    @SuppressWarnings("unchecked")
    private static List<Object> newListForTarget(Class<?> targetType, int size) {
        if (targetType == List.class || targetType == Collection.class || targetType == Iterable.class
                || targetType.isInterface() || Modifier.isAbstract(targetType.getModifiers())) {
            return new ArrayList<>(size);
        }
        if (targetType == ArrayList.class) {
            return new ArrayList<>(size);
        }
        if (targetType == LinkedList.class) {
            return new LinkedList<>();
        }
        if (targetType == Vector.class) {
            return new Vector<>(size);
        }
        if (targetType == Stack.class) {
            return new Stack<>();
        }
        try {
            Object instance = targetType.getDeclaredConstructor().newInstance();
            if (instance instanceof List) {
                return (List<Object>) instance;
            }
        } catch (Exception ignored) {
            // 反射失败时回退为 ArrayList，保证可写入。
        }
        return new ArrayList<>(size);
    }

    @SuppressWarnings("unchecked")
    private static Set<Object> newSetForTarget(Class<?> targetType, int size) {
        if (targetType == Set.class || targetType == Collection.class || targetType == Iterable.class
                || targetType.isInterface() || Modifier.isAbstract(targetType.getModifiers())) {
            return new LinkedHashSet<>(size);
        }
        if (targetType == HashSet.class || targetType == LinkedHashSet.class) {
            return new LinkedHashSet<>(size);
        }
        if (targetType == TreeSet.class) {
            return new TreeSet<>();
        }
        try {
            Object instance = targetType.getDeclaredConstructor().newInstance();
            if (instance instanceof Set) {
                return (Set<Object>) instance;
            }
        } catch (Exception ignored) {
            // 反射失败时回退为 LinkedHashSet，保证可写入且保留顺序。
        }
        return new LinkedHashSet<>(size);
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> newCollectionForTarget(Class<?> targetType, int size) {
        if (targetType.isInterface() || Modifier.isAbstract(targetType.getModifiers())) {
            if (Set.class.isAssignableFrom(targetType)) {
                return new LinkedHashSet<>(size);
            }
            if (Queue.class.isAssignableFrom(targetType) || Deque.class.isAssignableFrom(targetType)) {
                return new LinkedList<>();
            }
            return new ArrayList<>(size);
        }
        try {
            Object instance = targetType.getDeclaredConstructor().newInstance();
            if (instance instanceof Collection) {
                return (Collection<Object>) instance;
            }
        } catch (Exception ignored) {
            // 反射失败时按接口类型回退。
        }
        if (Set.class.isAssignableFrom(targetType)) {
            return new LinkedHashSet<>(size);
        }
        if (Queue.class.isAssignableFrom(targetType) || Deque.class.isAssignableFrom(targetType)) {
            return new LinkedList<>();
        }
        return new ArrayList<>(size);
    }

    private static Object convertGenericValue(Object value, Type targetType) throws Exception {
        if (value == null || targetType == null || targetType == Object.class) {
            return value;
        }
        if (targetType instanceof Class<?>) {
            return convertMapValueToTarget(value, (Class<?>) targetType, targetType);
        }
        if (targetType instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) targetType;
            Type rawType = parameterized.getRawType();
            if (rawType instanceof Class<?>) {
                return convertMapValueToTarget(value, (Class<?>) rawType, parameterized);
            }
        }
        if (targetType instanceof GenericArrayType) {
            GenericArrayType arrayType = (GenericArrayType) targetType;
            Type componentType = arrayType.getGenericComponentType();
            Class<?> componentClass = componentClassOf(componentType);
            List<?> source;
            if (value instanceof Collection) {
                source = new ArrayList<>((Collection<?>) value);
            } else if (value != null && value.getClass().isArray()) {
                int len = Array.getLength(value);
                List<Object> tmp = new ArrayList<>(len);
                for (int i = 0; i < len; i++) {
                    tmp.add(Array.get(value, i));
                }
                source = tmp;
            } else {
                source = Collections.singletonList(value);
            }
            Object array = Array.newInstance(componentClass, source.size());
            int i = 0;
            for (Object item : source) {
                Array.set(array, i++, convertGenericValue(item, componentType));
            }
            return array;
        }
        return value;
    }

    private static Class<?> componentClassOf(Type componentType) {
        if (componentType instanceof Class<?>) {
            return (Class<?>) componentType;
        }
        if (componentType instanceof ParameterizedType) {
            Type rawType = ((ParameterizedType) componentType).getRawType();
            if (rawType instanceof Class<?>) {
                return (Class<?>) rawType;
            }
        }
        return Object.class;
    }

    private static Type collectionElementType(Type genericType) {
        if (genericType instanceof ParameterizedType) {
            Type[] arguments = ((ParameterizedType) genericType).getActualTypeArguments();
            if (arguments.length == 1) return arguments[0];
        }
        return null;
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
                if (isIgnoredField(sourceObj.getClass(), property)) {
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
            if (isIgnoredField(sourceObj.getClass(), sourceProperty)) {
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

    private static boolean isIgnoredField(Class<?> clazz, java.beans.PropertyDescriptor property) {
        return isIgnoredField(clazz, property.getName(), property);
    }

    private static boolean isIgnoredField(Class<?> clazz, String fieldName) {
        return isIgnoredField(clazz, fieldName, null);
    }

    /**
     * 字段注解与 getter/setter 方法注解任一命中即视为忽略。
     * 无 backing field 的派生属性（如 getPendingEvents）只能靠方法注解排除，
     * 否则会被 entityToMap 序列化进快照与 modelSnapshot。
     */
    private static boolean isIgnoredField(Class<?> clazz, String fieldName,
                                          java.beans.PropertyDescriptor property) {
        try {
            java.lang.reflect.Field field = findField(clazz, fieldName);
            if (field != null && field.isAnnotationPresent(IgnoreField.class)) {
                return true;
            }
        } catch (Exception ignored) {
            // 字段查找失败时继续按方法注解判断，避免派生属性被误序列化。
        }
        if (property != null) {
            if (property.getReadMethod() != null
                    && property.getReadMethod().isAnnotationPresent(IgnoreField.class)) {
                return true;
            }
            if (property.getWriteMethod() != null
                    && property.getWriteMethod().isAnnotationPresent(IgnoreField.class)) {
                return true;
            }
        }
        return false;
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
