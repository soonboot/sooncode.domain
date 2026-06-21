package com.sooncode.project.core.model;

import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.monitor.FuncType;
import com.sooncode.project.core.monitor.Monitor;
import com.sooncode.project.core.session.ISession;
import com.sooncode.project.core.session.SessionManager;
import com.sooncode.project.core.validator.IValidate;
import com.sooncode.project.core.validator.ModelValidateFailException;

import java.util.ArrayList;
import java.util.List;

/**
 * 领域模型存储库实现类
 *
 * @param <T>
 */
public class DomainRepository<T extends DomainModel> implements IDomainRepository<T> {
    protected IEventStore eventStore;

    /**
     * 构造器
     *
     * @param eventStore 事件存储器对象
     */
    public DomainRepository(IEventStore eventStore) {
        this.eventStore = eventStore;
    }

    private DomainRepository() {
    }

    /**
     * 通过ID在快照中查找一个实体
     *
     * @param id     实体的ID
     * @param tClass 实体的类型
     * @return
     */
    @Override
    public T findByID(String id, Class<T> tClass) {
        String streamName = streamNameFor(tClass, id);
        DomainModel snapshot = eventStore.getLatestSnapshot(streamName, tClass);
        T entity = null;
        if (snapshot != null) {
            entity = (T) snapshot;
        } else {
            return null;
        }
        return entity;
    }

    /**
     * 增加一个实体的元数据, 同时增加实体快照与事件溯源存储对象
     *
     * @param entity 实体
     */
    @Override
    public void add(T entity) {
        add(entity, null, true);
    }

    @Override
    public void add(T entity, IGenerateReport report) {
        add(entity, report, true);
    }

    @Override
    public void add(T entity, IGenerateReport report, boolean monitor) {
        if (entity.stored) return;
        validateEntity(entity, FuncType.add);
        String streamName = streamNameFor(entity.getClass(), entity.getId());
        if (SessionManager.contains(entity)) {
            ISession session = SessionManager.Get(entity);
            session.setSessionFunction(() -> {
                saveSnapshot(entity);
                eventStore.createNewStream(streamName, entity.events, entity.getClass());
            });
        } else {
            saveSnapshot(entity);
            eventStore.createNewStream(streamName, entity.events, entity.getClass());
        }
        entity.stored = true;
        try {
            if (report != null)
                report.add(entity);
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
        if (monitor && Monitor.instance != null) {
            try {
                Monitor.instance.Notice((T) entity, FuncType.add);
            } catch (Exception ex) {
                ex.printStackTrace();
                throw ex;
            }
        }
    }

    /**
     * 保存事件流,并更新快照数据
     *
     * @param entity 实体
     */
    @Override
    public void save(T entity) {
        save(entity, null, true);
    }

    @Override
    public void save(T entity, IGenerateReport report) {

        save(entity, report, true);

    }

    @Override
    public void save(T entity, IGenerateReport report, boolean monitor) {
        if (entity.stored) return;
        validateEntity(entity, FuncType.modify);
        T oldEntity = findByID(entity.getId(), (Class<T>) entity.getClass());
        String streamName = streamNameFor(entity.getClass(), entity.getId());
        if (SessionManager.contains(entity)) {
            ISession session = SessionManager.Get(entity);
            session.setSessionFunction(() -> {
                saveSnapshot(entity);
                eventStore.appendEventToStream(streamName, entity.events, getExpectedVersion(entity.startVersion), (Class<T>) entity.getClass());
            });
        } else {
            saveSnapshot(entity);
            eventStore.appendEventToStream(streamName, entity.events, getExpectedVersion(entity.startVersion), (Class<T>) entity.getClass());
        }

        entity.stored = true;
        try {
            if (report != null)
                report.modify(entity);
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
        if (monitor && Monitor.instance != null) {
            try {

                Monitor.instance.Notice((T) entity, (T) oldEntity, FuncType.modify);
            } catch (Exception ex) {
                ex.printStackTrace();
                throw ex;
            }
        }
    }

    /**
     * 删除实体对象, 使用实体的元数据失效
     *
     * @param entity 实体
     */
    @Override
    public void delete(T entity) {
        delete(entity, null, true);
    }

    @Override
    public void delete(T entity, IGenerateReport report) {
        delete(entity, report, true);
    }

    @Override
    public void delete(T entity, IGenerateReport report, boolean monitor) {
        if (entity.stored) return;
        validateEntity(entity, FuncType.delete);
        String streamName = streamNameFor(entity.getClass(), entity.getId());
        if (SessionManager.contains(entity)) {
            ISession session = SessionManager.Get(entity);
            session.setSessionFunction(() -> {
                deleteSnapshot(entity);
                eventStore.invalid(streamName, entity.events, getExpectedVersion(entity.startVersion), (Class<T>) entity.getClass());
            });
        } else {
            deleteSnapshot(entity);
            eventStore.invalid(streamName, entity.events, getExpectedVersion(entity.startVersion), (Class<T>) entity.getClass());
        }
        entity.stored = true;
        try {
            if (report != null)
                report.delete(entity);
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
        if (monitor && Monitor.instance != null) {
            try {
                Monitor.instance.Notice((T) entity, FuncType.delete);
            } catch (Exception ex) {
                ex.printStackTrace();
                throw ex;
            }
        }
    }

    @Override
    public Page<EventWrapper> getEventStream(Class modelClass, Class cla, Creater creater, int pageSize, int pageIndex) {
        String createrId = null;
        String streamType = null;
        String modelType = null;
        if (modelClass != null)
            modelType = modelClass.getName();
        if (creater != null)
            createrId = creater.getId();
        if (cla != null)
            streamType = cla.getName();
        return eventStore.getStream(modelType, streamType, createrId, pageSize, pageIndex);
    }

    /**
     * 重放事件流, 从快照中获取最后版本,如果没有快照, 就从事件流中第一个事件开始重放
     *
     * @param id     体实ID
     * @param tClass 实体类型
     * @return
     */
    @Override
    public T replay(String id, Class<T> tClass, int toVersion) {
        String streamName = streamNameFor(tClass, id);
        int fromEventNumber = 0;
        int toEventNumber = toVersion;
        DomainModel snapshot = eventStore.getLatestSnapshot(streamName, tClass);
        T entity = null;
        if (snapshot != null) {
            entity = (T) snapshot;
        } else {
            try {
                entity = tClass.newInstance();
                ((Entity) entity).setId(id);
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }
        }
        List<DomainEvent> events = eventStore.getStream(streamName, fromEventNumber, toEventNumber);
        ((DomainModel<T>) entity).replay(events, fromEventNumber, toEventNumber);
        //*查找大于快照版本的事件,并重放事件.
        if (Monitor.instance != null) {
            try {
                Monitor.instance.Notice((T) entity, FuncType.replay);
            } catch (Exception ex) {
                ex.printStackTrace();
                throw ex;
            }

        }
        return entity;
    }

    /**
     * 保存快照
     *
     * @param entity 实体
     */
    @Override
    public void saveSnapshot(Entity entity) {
        String id = streamNameFor(entity.getClass(), entity.getId());
        eventStore.saveSnapshot(id, entity);
    }

    @Override
    public void deleteSnapshot(Entity entity) {
        Class<?> cla = entity.getClass();
        String streamId = streamNameFor(cla, entity.getId());
        eventStore.deleteSnapshot(streamId, cla);
    }

    @Override
    public List<T> getSnapshotList(Class<T> tClass) {
        List<T> snapshotList = eventStore.getSnapshotList(tClass.getName(),tClass);
        List<T> result = new ArrayList<>();
        for (T snapshot : snapshotList) {
            T entity = null;
            if (snapshot != null) {
                entity = (T) snapshot;
            }
            result.add(entity);
        }
        return result;
    }

    private Integer getExpectedVersion(int startVersion) {
        return startVersion == 0 ? null : startVersion;
    }

    private String streamNameFor(Class c, String id) {
        return String.format("%s-%s", c.getName(), id);
    }

    private void validateEntity(DomainModel entity, FuncType funcType) {
        if (entity instanceof IValidate) {
            IValidate validate = (IValidate) entity;
            ModelValidateFailException exception = validate.validate(funcType);
            if (exception != null)
                throw exception;
        }
    }
}
