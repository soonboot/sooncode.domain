package com.sooncode.project.core.finder;

import com.sooncode.project.core.annotations.IgnoreField;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.IDomainRepository;
import com.sooncode.project.core.model.ValueObject;
import com.sooncode.project.core.monitor.Monitor;
import com.sooncode.project.core.utils.ReflectUtils;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

class Find<T  extends DomainModel<T>> implements IFind<T>{
    private IDomainRepository<T> repository;
    private IAddField<T> addField;
    private Class<T> cla;
    Find(IAddField<T> addField,Class<T> cla){
        this.cla=cla;
        this.repository= Monitor.instance.getDomainRepository();
        this.addField=addField;
    }
    private Find(){}
    @Override
    public T byId(String id){
        return repository.findByID(id,cla);
    }
    @Override
    public IFindWrapper<T> byField(String name, Object value) {
        return addField.add(name,value);
    }

    @Override
    public IFindWrapper<T> byField(String name, Object value, OType type) {
        return addField.add(name,value,type);
    }

    @Override
    public IFindWrapper<T> byMap(Map<String, Object> map) {
        return addField.add(map);
    }

    /**
     * 以实体模板（query by example）构造查询条件。
     * <p>
     * 只取"业务属性"参与匹配：标了 {@link IgnoreField} 的基础设施属性（events、stored、
     * persistedEventCount、version、pendingEvents）不落快照，若作为条件参与查询将永远匹配不到任何文档
     * （快照里没有这些字段）。过滤规则与 {@code EntityConvert.entityToMap} 保持同源，
     * 避免"写进去用一套规则、查出来用另一套规则"。
     * <p>
     * 同时排除 {@code id}：{@code Entity} 的构造器会为每个新实例生成随机 UUID，
     * 模板对象的 id 恒非空且必然与已存实体不同。按 id 查询请使用 {@link #byId(String)}。
     * <p>
     * 条件类型沿用 {@code byMap} 的默认语义：字符串走模糊匹配（{@code contains}），
     * 非字符串走 {@code eq}；需要精确匹配字符串请改用 {@link #byField(String, Object, OType)}。
     */
    @Override
    public IFindWrapper<T> byModel(DomainModel<T> model) {
        if (model == null) return addField.add(null);
        PropertyDescriptor[] properties=ReflectUtils.getBeanGetters(model.getClass());
        HashMap<String, Object> map=new HashMap<>();
        try {
            for (PropertyDescriptor p : properties) {
                if (p.getReadMethod() == null) continue;
                if (isInfrastructureProperty(model.getClass(), p)) continue;
                Object o = p.getReadMethod().invoke(model);
                if (o!=null) map.put(p.getName(),o);
            }
        }catch (Exception ex){
            com.sooncode.project.core.model.DomainException de =
                    new com.sooncode.project.core.model.DomainException("byModel 读取实体属性失败:" + model.getClass().getName());
            de.initCause(ex);
            throw de;
        }
        return addField.add(map);
    }

    /**
     * 判断属性是否属于"不参与查询"的基础设施属性。
     * 判定规则与 {@code EntityConvert.isIgnoredField} 一致：字段注解或 getter/setter 注解任一命中即忽略；
     * 额外排除聚合根主键 id（模板的 id 由构造器随机生成，参与查询必然零命中）。
     */
    private static boolean isInfrastructureProperty(Class<?> clazz, PropertyDescriptor p) {
        if ("id".equals(p.getName())) return true;
        Field field = findField(clazz, p.getName());
        if (field != null && field.isAnnotationPresent(IgnoreField.class)) return true;
        return (p.getReadMethod() != null && p.getReadMethod().isAnnotationPresent(IgnoreField.class))
                || (p.getWriteMethod() != null && p.getWriteMethod().isAnnotationPresent(IgnoreField.class));
    }

    private static Field findField(Class<?> clazz, String name) {
        while (clazz != null && clazz != Object.class) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

}
