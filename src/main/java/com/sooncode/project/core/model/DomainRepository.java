package com.sooncode.project.core.model;

import com.sooncode.project.core.annotations.SkipEventSourcing;
import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.monitor.FuncType;
import com.sooncode.project.core.monitor.Monitor;
import com.sooncode.project.core.session.ISession;
import com.sooncode.project.core.session.SessionManager;
import com.sooncode.project.core.validator.IValidate;
import com.sooncode.project.core.validator.ModelValidateFailException;

import com.alibaba.fastjson.JSONObject;
import com.sooncode.project.core.recycle.RecycleBinRecord;
import com.sooncode.project.core.recycle.RecycleBinRepository;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 领域模型存储库实现类
 *
 * @param <T>
 */
public class DomainRepository<T extends DomainModel> implements IDomainRepository<T> {
    protected IEventStore eventStore;
    protected RecycleBinRepository recycleBinRepository;

    public void setRecycleBinRepository(RecycleBinRepository recycleBinRepository) {
        this.recycleBinRepository = recycleBinRepository;
    }

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
        if (entity.isStored()) return;
        validateEntity(entity, FuncType.add);
        String streamName = streamNameFor(entity.getClass(), entity.getId());
        boolean skipES = isSkipEventSourcing(entity);
        if (SessionManager.contains(entity)) {
            ISession session = SessionManager.Get(entity);
            session.setSessionFunction(() -> {
                saveSnapshot(entity);
                if (!skipES) {
                    eventStore.createNewStream(streamName, entity.events, entity.getClass());
                }
            });
        } else {
            saveSnapshot(entity);
            if (!skipES) {
                eventStore.createNewStream(streamName, entity.events, entity.getClass());
            }
        }
        entity.markStored();
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
        if (entity.isStored()) return;
        validateEntity(entity, FuncType.modify);
        T oldEntity = findByID(entity.getId(), (Class<T>) entity.getClass());
        String streamName = streamNameFor(entity.getClass(), entity.getId());
        boolean skipES = isSkipEventSourcing(entity);
        if (SessionManager.contains(entity)) {
            ISession session = SessionManager.Get(entity);
            session.setSessionFunction(() -> {
                saveSnapshot(entity);
                if (!skipES) {
                    eventStore.appendEventToStream(streamName, entity.events, getExpectedVersion(entity.startVersion), (Class<T>) entity.getClass());
                }
            });
        } else {
            saveSnapshot(entity);
            if (!skipES) {
                eventStore.appendEventToStream(streamName, entity.events, getExpectedVersion(entity.startVersion), (Class<T>) entity.getClass());
            }
        }

        entity.markStored();
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
        if (entity.isStored()) return;
        validateEntity(entity, FuncType.delete);
        String streamName = streamNameFor(entity.getClass(), entity.getId());
        saveToRecycleBin(entity, streamName);
        boolean skipES = isSkipEventSourcing(entity);
        if (SessionManager.contains(entity)) {
            ISession session = SessionManager.Get(entity);
            session.setSessionFunction(() -> {
                deleteSnapshot(entity);
                if (!skipES) {
                    eventStore.invalid(streamName, entity.events, getExpectedVersion(entity.startVersion), (Class<T>) entity.getClass());
                }
            });
        } else {
            deleteSnapshot(entity);
            if (!skipES) {
                eventStore.invalid(streamName, entity.events, getExpectedVersion(entity.startVersion), (Class<T>) entity.getClass());
            }
        }
        entity.markStored();
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
        // 跳过事件溯源的实体：仅返回快照，不再拉取事件流
        if (isSkipEventSourcing(entity)) {
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

    private void saveToRecycleBin(T entity, String streamName) {
        if (recycleBinRepository == null) return;
        recycleBinRepository.save(entity.getClass(), streamName, entity.getId());
    }

    public T restore(String entityId, Class<T> tClass) {
        if (recycleBinRepository == null)
            throw new DomainException("RecycleBinRepository not configured");
        String streamName = streamNameFor(tClass, entityId);
        Document recycleDoc = recycleBinRepository.findByStreamId(streamName);
        if (recycleDoc == null)
            throw new DomainException("回收站未找到数据:" + streamName);
        Document snapshotDoc = (Document) recycleDoc.get("snapshotDoc");
        eventStore.reactivate(streamName);
        recycleBinRepository.restoreSnapshot(streamName, tClass);
        Document snapshot = (Document) snapshotDoc.get("snapshot");
        if (snapshot == null)
            throw new DomainException("回收站快照数据异常:" + streamName);
        JSONObject jsonObject = new JSONObject(snapshot);
        T entity = (T) jsonObject.toJavaObject(tClass);
        entity.markStored();
        return entity;
    }

    public Page<RecycleBinRecord> listTrash(Class<T> tClass, int pageIndex, int pageSize) {
        if (recycleBinRepository == null)
            throw new DomainException("RecycleBinRepository not configured");
        String entityType = tClass.getName();
        List<Document> docs = recycleBinRepository.listByEntityType(entityType, pageIndex, pageSize);
        long total = recycleBinRepository.countByEntityType(entityType);
        return buildTrashPage(docs, total, pageIndex, pageSize);
    }

    public long countTrash(Class<T> tClass) {
        if (recycleBinRepository == null)
            throw new DomainException("RecycleBinRepository not configured");
        return recycleBinRepository.countByEntityType(tClass.getName());
    }

    public Page<RecycleBinRecord> listAllTrash(int pageIndex, int pageSize) {
        if (recycleBinRepository == null)
            throw new DomainException("RecycleBinRepository not configured");
        List<Document> docs = recycleBinRepository.listAll(pageIndex, pageSize);
        long total = recycleBinRepository.countAll();
        return buildTrashPage(docs, total, pageIndex, pageSize);
    }

    public long countAllTrash() {
        if (recycleBinRepository == null)
            throw new DomainException("RecycleBinRepository not configured");
        return recycleBinRepository.countAll();
    }

    private Page<RecycleBinRecord> buildTrashPage(List<Document> docs, long total, int pageIndex, int pageSize) {
        List<RecycleBinRecord> records = new ArrayList<>();
        for (Document doc : docs) {
            RecycleBinRecord record = new RecycleBinRecord();
            record.setId(doc.getObjectId("_id").toHexString());
            record.setStreamId(doc.getString("streamId"));
            record.setEntityId(doc.getString("entityId"));
            record.setEntityType(doc.getString("entityType"));
            record.setSnapshotDoc((Map<String, Object>) doc.get("snapshotDoc"));
            record.setDeleteTime(doc.getDate("deleteTime"));
            records.add(record);
        }
        Page<RecycleBinRecord> page = new Page<>();
        page.setTotalElements(total);
        page.setPageIndex(pageIndex);
        page.setPageSize(pageSize);
        page.setContent(records);
        return page;
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

    /**
     * 判断实体是否标记了 {@link SkipEventSourcing} 且 value() == true。
     * 无注解或注解 value() == false → 走完整 ES。
     */
    private boolean isSkipEventSourcing(DomainModel entity) {
        SkipEventSourcing ann = entity.getClass().getAnnotation(SkipEventSourcing.class);
        return ann != null && ann.value();
    }
}
