package com.sooncode.project.core.model;

import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.annotations.IgnoreField;
import com.sooncode.project.core.annotations.NotRequired;
import com.sooncode.project.core.utils.BaseTypeConvert;
import com.sooncode.project.core.utils.EntityConvert;
import com.sooncode.project.core.utils.ReflectUtils;

import java.beans.PropertyDescriptor;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 领域事件基类
 *
 * <p>设计要点：
 * <ul>
 *   <li>实字段通过反射缓存（Class → Map&lt;name, PropertyDescriptor&gt;），
 *       子类继承链上的字段也包含在内。</li>
 *   <li>convertParam(Entity) 改用 EntityConvert.entityToMap 序列化聚合根，
 *       自动处理嵌套对象/循环引用；不会覆盖聚合根内部字段（events/version/stored）。</li>
 *   <li>set 严格模式：未标 @EventBoot 时遇到未知字段直接抛 DomainException，
 *       避免拼写错误静默丢失。</li>
 *   <li>projectiveEntity 只回写实字段，dynamicParams 字段不回写。</li>
 * </ul>
 */
public abstract class DomainEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final Map<Class<?>, Map<String, PropertyDescriptor>> PROPERTIES_CACHE = new ConcurrentHashMap<>();

    private String id;
    private Map<String, Object> dynamicParams = new LinkedHashMap<>();

    public DomainEvent() {
        ensurePropertiesLoaded();
    }

    public DomainEvent(String aggregateId) {
        ensurePropertiesLoaded();
        setId(aggregateId);
    }

    public DomainEvent(String aggregateId, Entity obj) {
        this(obj);
        setId(aggregateId);
    }

    public DomainEvent(String aggregateId, Map<String, Object> map) {
        this(map);
        setId(aggregateId);
    }

    public DomainEvent(Entity obj) {
        ensurePropertiesLoaded();
        convertParam(obj);
    }

    public DomainEvent(Map<String, Object> map) {
        ensurePropertiesLoaded();
        convertParam(map);
    }

    /**
     * 获取本类（含父类）所有实字段的 PropertyDescriptor。
     * 第一次调用时反射一次，结果按字段名字母序缓存到 PROPERTIES_CACHE。
     */
    private static Map<String, PropertyDescriptor> getDeclaredProperties(Class<?> clazz) {
        Map<String, PropertyDescriptor> cached = PROPERTIES_CACHE.get(clazz);
        if (cached != null) {
            return cached;
        }
        PropertyDescriptor[] descriptors = ReflectUtils.getBeanProperties(clazz);
        Map<String, PropertyDescriptor> map = new LinkedHashMap<>();
        for (PropertyDescriptor pd : descriptors) {
            String name = pd.getName();
            if ("dynamicParams".equals(name) || "id".equals(name)) {
                continue;
            }
            map.put(name, pd);
        }
        Map<String, PropertyDescriptor> sorted = new LinkedHashMap<>();
        map.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        PROPERTIES_CACHE.put(clazz, sorted);
        return sorted;
    }

    private void ensurePropertiesLoaded() {
        getDeclaredProperties(this.getClass());
    }

    /**
     * 沿继承链查找字段（覆盖父类私有字段）。
     */
    private static Field findField(Class<?> clazz, String fieldName) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 校验参数：必填字段必须在 map 中；标了 IgnoreField 则从 map 中移除（不参与回填）；
     * 标了 NotRequired 则可缺失。
     */
    private void checkParam(String fieldName, Map<String, Object> map) {
        Field field = findField(this.getClass(), fieldName);
        if (field == null) {
            throw new DomainException("字段不存在：" + fieldName + " on " + this.getClass().getName());
        }
        if (field.isAnnotationPresent(IgnoreField.class)) {
            map.remove(fieldName);
            return;
        }
        if (field.isAnnotationPresent(NotRequired.class)) {
            return;
        }
        if (!map.containsKey(fieldName)) {
            throw new DomainException("缺少参数：" + fieldName);
        }
    }

    /**
     * 把聚合根序列化为 map（走 EntityConvert.entityToMap，自动处理嵌套/循环引用），
     * 然后调用 convertParam(Map) 做参数校验与回填。
     */
    void convertParam(Entity obj) {
        Map<String, Object> map = EntityConvert.entityToMap(obj);
        convertParam(map);
    }

    /**
     * 校验并回填 map 中的字段值到事件。
     * 校验顺序：先 @EventBoot.Params 显式声明的参数，再所有实字段；最后批量 set。
     */
    void convertParam(Map<String, Object> map) {
        if (map == null) {
            map = new LinkedHashMap<>();
        }
        if (this.getClass().isAnnotationPresent(EventBoot.class)) {
            String[] params = this.getClass().getAnnotation(EventBoot.class).Params();
            for (String p : params) {
                checkParam(p, map);
            }
        }
        Map<String, PropertyDescriptor> declared = getDeclaredProperties(this.getClass());
        for (String fieldName : declared.keySet()) {
            checkParam(fieldName, map);
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            set(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 将事件中的字段回写到聚合根。
     * <ul>
     *   <li>优先按实字段（properties）回写；</li>
     *   <li>事件实字段为空、dynamicParams 非空时（如 BasicAddEvent），把 dynamicParams 中的键
     *       视为"业务字段"，按聚合根 setter 回写（类型推断走 setPropertyValue）。</li>
     * </ul>
     */
    public void projectiveEntity(DomainModel en) {
        Map<String, PropertyDescriptor> declared = getDeclaredProperties(this.getClass());
        PropertyDescriptor[] entityProperties = ReflectUtils.getBeanProperties(en.getClass());
        // 1) 实字段回写
        for (PropertyDescriptor property : entityProperties) {
            if (property.getWriteMethod() == null) {
                continue;
            }
            String name = property.getName();
            if ("id".equals(name)) {
                continue;
            }
            if (!declared.containsKey(name)) {
                continue;
            }
            Object value = get(name);
            if (value == null) {
                continue;
            }
            setPropertyValue(en, property, value);
        }
        // 2) dynamicParams 回写（用于 Basic* 通用事件：实字段为空，业务字段全在 dynamicParams）
        if (!dynamicParams.isEmpty()) {
            for (PropertyDescriptor property : entityProperties) {
                if (property.getWriteMethod() == null) {
                    continue;
                }
                String name = property.getName();
                if ("id".equals(name)) {
                    continue;
                }
                if (!dynamicParams.containsKey(name)) {
                    continue;
                }
                Object value = dynamicParams.get(name);
                if (value == null) {
                    continue;
                }
                setPropertyValue(en, property, value);
            }
        }
    }

    /**
     * 设置事件字段值。
     * <ul>
     *   <li>命中实字段 → setPropertyValue</li>
     *   <li>未命中：
     *     <ul>
     *       <li>类标注 @EventBoot 且字段在 Params 中 → 写入 dynamicParams</li>
     *       <li>类标注 @EventBoot 且 KeepAll=true → 写入 dynamicParams（带 warn 日志）</li>
     *       <li>其他情况 → 抛 DomainException（避免拼写错误静默丢失）</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    public void set(String fieldName, Object value) {
        Map<String, PropertyDescriptor> declared = getDeclaredProperties(this.getClass());
        if (!declared.containsKey(fieldName)) {
            handleUnknownField(fieldName, value);
            return;
        }
        if (value == null) {
            return;
        }
        PropertyDescriptor property = declared.get(fieldName);
        setPropertyValue(this, property, value);
    }

    private void handleUnknownField(String fieldName, Object value) {
        EventBoot eb = this.getClass().getAnnotation(EventBoot.class);
        if (eb == null) {
            throw new DomainException("事件字段不存在：" + fieldName
                    + " on " + this.getClass().getName()
                    + "（如需写入动态参数，请在类上标注 @EventBoot 并把字段加入 Params）");
        }
        for (String s : eb.Params()) {
            if (s.equals(fieldName)) {
                this.dynamicParams.put(fieldName, value);
                return;
            }
        }
        if (eb.KeepAll()) {
            // 动态参数不在事件实字段中，projectiveEntity 不会回写到聚合根。
            System.err.println("[DomainEvent WARN] 字段 " + fieldName
                    + " 不在 " + this.getClass().getSimpleName()
                    + " 的实字段中，将以 dynamicParams 形式保存，但不会回写到聚合根。");
            this.dynamicParams.put(fieldName, value);
            return;
        }
        throw new DomainException("事件字段不存在：" + fieldName
                + " on " + this.getClass().getName()
                + "（如需写入动态参数，请在 @EventBoot.Params 中声明）");
    }

    /**
     * 写入目标对象的属性。
     * 嵌套类型（Map/List/DomainModel/ValueObject）走 EntityConvert.mapToEntity 路径；
     * 单值类型走 BaseTypeConvert.convertValue；
     * 其余尝试直接写入，类型不匹配时降级 toString 转换。
     */
    private void setPropertyValue(Object target, PropertyDescriptor property, Object value) {
        Class<?> propertyType = property.getPropertyType();
        if (property.getWriteMethod() == null) {
            return;
        }
        try {
            if (value == null) {
                property.getWriteMethod().invoke(target, (Object) null);
                return;
            }
            if (propertyType.isInstance(value)) {
                property.getWriteMethod().invoke(target, value);
                return;
            }
            if (BaseTypeConvert.isSingleValueType(value)) {
                property.getWriteMethod().invoke(target, BaseTypeConvert.convertValue(value, propertyType));
                return;
            }
            if ((value instanceof Map || value instanceof List)
                    && (Map.class.isAssignableFrom(propertyType) || List.class.isAssignableFrom(propertyType))) {
                property.getWriteMethod().invoke(target, value);
                return;
            }
            if (value instanceof Map
                    && (DomainModel.class.isAssignableFrom(propertyType)
                        || ValueObject.class.isAssignableFrom(propertyType)
                        || SimpleObject.class.isAssignableFrom(propertyType))) {
                Object nested = propertyType.getDeclaredConstructor().newInstance();
                EntityConvert.mapToEntity((Map<String, Object>) value, nested);
                property.getWriteMethod().invoke(target, nested);
                return;
            }
            // 兜底：尝试 toString 转换
            property.getWriteMethod().invoke(target, BaseTypeConvert.convertValue(value, propertyType));
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw wrap("对象转换失败", property.getName(), e.getTargetException());
        } catch (java.lang.IllegalAccessException e) {
            throw wrap("对象转换失败", property.getName(), e);
        } catch (Exception e) {
            throw wrap("对象转换失败", property.getName(), e);
        }
    }

    public Object get(String fieldName) {
        Map<String, PropertyDescriptor> declared = getDeclaredProperties(this.getClass());
        if (declared.containsKey(fieldName)) {
            try {
                return declared.get(fieldName).getReadMethod().invoke(this);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw wrap("读取事件字段失败", fieldName, e.getTargetException());
            } catch (java.lang.IllegalAccessException e) {
                throw wrap("读取事件字段失败", fieldName, e);
            }
        }
        return this.dynamicParams.get(fieldName);
    }

    public boolean hasField(String fieldName) {
        return getDeclaredProperties(this.getClass()).containsKey(fieldName)
                || dynamicParams.containsKey(fieldName);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Map<String, Object> getDynamicParams() {
        return Collections.unmodifiableMap(dynamicParams);
    }

    public void setDynamicParams(Map<String, Object> dynamicParams) {
        this.dynamicParams = dynamicParams == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(dynamicParams);
    }

    private static DomainException wrap(String action, String field, Throwable cause) {
        String msg = action + "，字段：" + field
                + " | " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
        DomainException de = new DomainException(msg);
        de.initCause(cause);
        return de;
    }
}
