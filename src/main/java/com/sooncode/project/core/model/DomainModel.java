package com.sooncode.project.core.model;

import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.annotations.IgnoreField;
import com.sooncode.project.core.finder.Finder;
import com.sooncode.project.core.generic.BasicAddEvent;
import com.sooncode.project.core.generic.BasicDeleteEvent;
import com.sooncode.project.core.generic.BasicModifyEvent;
import com.sooncode.project.core.generic.ReplayEvent;
import com.sooncode.project.core.monitor.FuncType;
import com.sooncode.project.core.monitor.Monitor;
import com.sooncode.project.core.utils.EntityConvert;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 聚合基类，也是事件溯源模式的聚合基类。
 */
public abstract class DomainModel<T> extends Entity {
    /** 事件流。 防止其被 entityToMap 序列化。 */
    @IgnoreField
    protected List<DomainEvent> events;
    /** 持久化标志：true 表示已落库，false 表示有未持久化事件。包级默认可见，由同包持久层读取。 */
    boolean stored = true;
    private int version;
    public int startVersion = 0;

    public DomainModel() {
        super();
        events = new ArrayList<>();
    }

    /**
     * 业务方法：触发"添加"语义事件。
     */
    public void add() {
        causes(new BasicAddEvent(), this);
    }

    public void update() {
        causes(new BasicModifyEvent(), this);
    }

    public void delete() {
        causes(new BasicDeleteEvent(), this);
    }

    /**
     * 应用一段事件流重放聚合根。要求传入的事件按 version 升序排列；本方法会再排一次保证稳定。
     *
     * @param events      事件列表
     * @param fromVersion 起始 version（仅记录，不参与过滤）
     * @param toVersion   结束 version（仅记录，不参与过滤）
     */
    public void replay(List<DomainEvent> events, int fromVersion, int toVersion) {
        if (events != null && !events.isEmpty()) {
            // 防御性排序：业务传进来的 List 可能未按 version 排序
            List<DomainEvent> sorted = new ArrayList<>(events);
            sorted.sort(Comparator.comparingInt(DomainModel::extractVersion));
            for (DomainEvent event : sorted) {
                apply(event);
            }
        }
        causes(new ReplayEvent(getId(), this, fromVersion, toVersion));
    }

    /**
     * 业务方法：从持久层拉取事件流重放聚合根。
     * 默认实现抛 UnsupportedOperationException，业务侧可重写为：
     * <pre>
     *   IEventStore store = ...;
     *   List&lt;DomainEvent&gt; list = store.loadEvents(getId());
     *   replay(list, 0, getVersion() - 1);
     * </pre>
     */
    public void replayFromStore() {
        throw new UnsupportedOperationException(
                "DomainModel.replayFromStore() 必须由业务子类重写，从 IEventStore 拉取事件流后调用 replay(List, int, int)。"
        );
    }

    /**
     * @deprecated 旧 API：仅做"自应用 ReplayEvent"，实际不接触持久层，version 凭空递增。业务侧应使用
     *             {@link #replay(List, int, int)} 或重写 {@link #replayFromStore()}。
     */
    @Deprecated
    public void replay(int toVersion) {
        causes(new ReplayEvent(getId(), this, 0, toVersion));
    }

    /**
     * @deprecated 见 {@link #replay(int)}。
     */
    @Deprecated
    public void replay(int fromVersion, int toVersion) {
        causes(new ReplayEvent(getId(), this, fromVersion, toVersion));
    }

    /**
     * @deprecated 见 {@link #replay(int)}。
     */
    @Deprecated
    public void replay() {
        causes(new ReplayEvent(getId(), this, 0, getVersion() - 1));
    }

    /**
     * ReplayEvent 携带聚合根快照。{@code when(ReplayEvent)} 把快照拷回聚合根。
     */
    protected void when(ReplayEvent event) {
        EntityConvert.copyPropertys(event.getData(), this);
    }

    /**
     * 应用事件：沿继承链查找 {@code when(MyEvent)}，命中则反射调用并递增 version；
     * 未命中则回退到 {@link DomainEvent#projectiveEntity(DomainModel)} 回写业务字段。
     * 任何反射异常都会被透传，调用方需在 {@link #causes(DomainEvent)} 捕获以做事件回滚。
     */
    private void apply(DomainEvent event) {
        Class<?> clz = getClass();
        Method method = null;
        while (clz != null) {
            try {
                method = clz.getDeclaredMethod("when", event.getClass());
                break;
            } catch (NoSuchMethodException ex) {
                clz = clz.getSuperclass();
            }
        }
        if (method == null) {
            event.projectiveEntity(this);
            addVersion();
            return;
        }
        boolean prevAccessible = method.canAccess(this);
        method.setAccessible(true);
        try {
            method.invoke(this, event);
            addVersion();
        } catch (DomainException ex) {
            throw ex;
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getTargetException();
            DomainException de = new DomainException("执行相关对象的when方法时错误: "
                    + (cause == null ? ex.getMessage() : cause.getMessage()));
            if (cause != null) {
                de.initCause(cause);
            }
            throw de;
        } catch (IllegalAccessException ex) {
            DomainException de = new DomainException("执行相关对象的when方法时错误: 访问不被允许");
            de.initCause(ex);
            throw de;
        } finally {
            method.setAccessible(prevAccessible);
        }
    }

    /**
     * 递增事件版本号（乐观锁依据）。返回递增后的值。
     */
    protected int addVersion() {
        version++;
        return version;
    }

    /* =================== 生命周期回调钩子 =================== */
    protected void beforeAdd(DomainEvent event) { }
    protected void afterAdd(DomainEvent event) { }
    protected void beforeUpdate(DomainEvent event) { }
    protected void afterUpdate(DomainEvent event) { }
    protected void beforeDelete(DomainEvent event) { }
    protected void afterDelete(DomainEvent event) { }
    protected void beforeStore(DomainEvent event) { }
    protected void afterStore(DomainEvent event) { }

    /* =================== causes 事件注册 =================== */

    /**
     * 事件起因：注册事件到 events 列表并应用。事件 id 为空时回填聚合根 id。
     */
    protected void causes(DomainEvent event) {
        if (event.getId() == null || event.getId().isEmpty()) {
            event.setId(this.getId());
        }
        events.add(event);
        stored = false;
        boolean applySuccess = false;
        try {
            apply(event);
            applySuccess = true;
        } finally {
            if (!applySuccess) {
                // apply 抛异常时回滚 events 列表，避免脏事件污染事件流
                events.remove(event);
            }
        }

        if (event.getClass().isAnnotationPresent(EventBoot.class) && Monitor.instance != null) {
            EventBoot eventBoot = event.getClass().getAnnotation(EventBoot.class);
            FuncType ft = eventBoot.StoreFunc();
            if (ft == FuncType.add) {
                beforeAdd(event);
            } else if (ft == FuncType.modify) {
                beforeUpdate(event);
            } else if (ft == FuncType.delete) {
                beforeDelete(event);
            }
            beforeStore(event);
            Monitor.instance.Store(this, eventBoot);
            if (ft == FuncType.add) {
                afterAdd(event);
            } else if (ft == FuncType.modify) {
                afterUpdate(event);
            } else if (ft == FuncType.delete) {
                afterDelete(event);
            }
            afterStore(event);
        }
        if (Monitor.instance != null) {
            Monitor.instance.Notice(event, this);
        }
    }

    protected void causes(DomainEvent event, Map<String, Object> params) {
        event.convertParam(params);
        causes(event);
    }

    protected void causes(DomainEvent event, Entity objParam) {
        event.convertParam(objParam);
        causes(event);
    }

    protected void causes(Class<? extends DomainEvent> cla) {
        DomainEvent event = getEvent(cla);
        causes(event);
    }

    protected void causes(Class<? extends DomainEvent> cla, Entity objParam) {
        DomainEvent event = getEvent(cla);
        event.convertParam(objParam);
        causes(event);
    }

    protected void causes(Class<? extends DomainEvent> cla, Map<String, Object> params) {
        DomainEvent event = getEvent(cla);
        event.convertParam(params);
        causes(event);
    }

    private DomainEvent getEvent(Class<? extends DomainEvent> cla) {
        try {
            return cla.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException ex) {
            DomainException de = new DomainException("生成事件异常，事件类必须有无参构造器：" + cla.getName());
            de.initCause(ex);
            throw de;
        } catch (InstantiationException ex) {
            DomainException de = new DomainException("生成事件异常，事件类不能是抽象类：" + cla.getName());
            de.initCause(ex);
            throw de;
        } catch (IllegalAccessException ex) {
            DomainException de = new DomainException("生成事件异常，事件类构造器不可访问：" + cla.getName());
            de.initCause(ex);
            throw de;
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getTargetException();
            DomainException de = new DomainException("生成事件异常，事件类构造器抛出异常：" + cla.getName());
            if (cause != null) {
                de.initCause(cause);
            }
            throw de;
        }
    }

    /* =================== 序列化与属性访问 =================== */

    public Map<String, Object> toMap() {
        return EntityConvert.entityToMap(this);
    }

    public void toEntity(Object targetObj) {
        EntityConvert.EntityToEntity(this, targetObj);
    }

    /**
     * 公开事件流（不可变视图），便于快照/审计。
     */
    public List<DomainEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    public int getVersion() {
        return version;
    }

    void setVersion(int version) {
        this.version = version;
    }

    /**
     * 持久化成功后由持久层调用，重置 stored 标志。业务代码不应直接调用。
     */
    public void markStored() {
        this.stored = true;
    }

    boolean isStored() {
        return stored;
    }

    /**
     * 从事件中提取 version（无 getter 约定的兜底为 0），用于 replay 排序。
     */
    private static int extractVersion(DomainEvent event) {
        if (event instanceof ReplayEvent) {
            return ((ReplayEvent) event).getFromVersion();
        }
        // DomainEvent 自身无 version 字段；这里依赖事件子类实现 getVersion 之类的约定
        try {
            Method m = event.getClass().getMethod("getVersion");
            Object v = m.invoke(event);
            if (v instanceof Integer) {
                return (Integer) v;
            }
            if (v instanceof Long) {
                return ((Long) v).intValue();
            }
        } catch (ReflectiveOperationException ignored) {
            // 事件类未实现 getVersion，fallback 到 0
        }
        return 0;
    }
}
