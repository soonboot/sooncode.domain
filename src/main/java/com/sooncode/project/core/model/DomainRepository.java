package com.sooncode.project.core.model;

import com.sooncode.project.core.annotations.SkipEventSourcing;
import com.sooncode.project.core.batcher.BatchOperation;
import com.sooncode.project.core.batcher.BatchRepository;
import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.monitor.FuncType;
import com.sooncode.project.core.monitor.Monitor;
import com.sooncode.project.core.session.DomainSession;
import com.sooncode.project.core.session.ISession;
import com.sooncode.project.core.session.SessionManager;
import com.sooncode.project.core.trash.Trash;
import com.sooncode.project.core.validator.IValidate;
import com.sooncode.project.core.validator.ModelValidateFailException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 领域模型存储库实现类
 *
 * @param <T>
 */
public class DomainRepository<T extends DomainModel> implements IDomainRepository<T> {
    protected IEventStore eventStore;
    protected Trash trashRepository;

    public void setTrashRepository(Trash trashRepository) {
        this.trashRepository = trashRepository;
    }

    /**
     * 构造器
     *
     * @param eventStore 事件存储器对象
     */
    public DomainRepository(IEventStore eventStore) {
        this(eventStore, new Trash(eventStore));
    }

    public DomainRepository(IEventStore eventStore, Trash trashRepository) {
        this.eventStore = eventStore;
        this.trashRepository = trashRepository;
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
            // 乐观锁必备：把当前持久化版本回填到 startVersion，供 save/delete 做 CAS 校验
            entity.startVersion = entity.getVersion();
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
        if (BatchRepository.capture(entity, FuncType.add, this)) return;
        if (entity.isStored()) return;
        String streamName = streamNameFor(entity.getClass(), entity.getId());
        boolean skipES = isSkipEventSourcing(entity);
        if (SessionManager.contains(entity)) {
            ISession session = SessionManager.Get(entity);
            // 会话内：收集为 BatchOperation，commit 时统一事务提交，保证原子性
            if (session instanceof DomainSession) {
                DomainSession ds = (DomainSession) session;
                BatchOperation op = new BatchOperation(
                        BatchOperation.Type.ADD, entity, null, null, null, skipES, true);
                ds.addOperation(op);
            } else {
                session.setSessionFunction(() -> {
                    saveSnapshot(entity, FuncType.add);
                    if (!skipES) {
                        createNewStream(entity, streamName);
                    } else {
                        entity.markEventsPersisted(entity.getPendingEvents());
                    }
                    entity.markStored();
                });
            }
        } else {
            // 单条原子路径：优先走批量事务（快照+事件+元数据同一事务，CAS 防并发）
            if (tryAtomicSingle(com.sooncode.project.core.batcher.BatchOperation.Type.ADD, entity, null, skipES)) {
                // batch 层已完成校验、快照、markEventsPersisted/markStored
            } else {
                // 无批量存储回退：非事务、非 CAS，仅为兼容旧配置。生产环境请通过 Monitor.ConfigDBConnection 配置Mongo以保证原子性
                // 为减少不一致窗口，先写事件/元数据再写快照
                if (!skipES) {
                    createNewStream(entity, streamName);
                } else {
                    entity.markEventsPersisted(entity.getPendingEvents());
                }
                saveSnapshot(entity, FuncType.add);
                entity.markStored();
            }
        }
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
        if (BatchRepository.capture(entity, FuncType.modify, this)) return;
        if (entity.isStored()) return;
        T oldEntity = findByID(entity.getId(), (Class<T>) entity.getClass());
        String streamName = streamNameFor(entity.getClass(), entity.getId());
        boolean skipES = isSkipEventSourcing(entity);
        if (SessionManager.contains(entity)) {
            ISession session = SessionManager.Get(entity);
            if (session instanceof DomainSession) {
                DomainSession ds = (DomainSession) session;
                Integer expected = entity.startVersion == 0 ? null : entity.startVersion;
                BatchOperation op = new BatchOperation(
                        BatchOperation.Type.MODIFY, entity, oldEntity, null, expected, skipES, true);
                ds.addOperation(op);
            } else {
                session.setSessionFunction(() -> {
                    saveSnapshot(entity, FuncType.modify);
                    if (!skipES) {
                        appendToStream(entity, streamName);
                    } else {
                        entity.markEventsPersisted(entity.getPendingEvents());
                    }
                    entity.markStored();
                });
            }
        } else {
            Integer expected = entity.startVersion == 0 ? null : entity.startVersion;
            if (tryAtomicSingle(BatchOperation.Type.MODIFY, entity, oldEntity, skipES, expected)) {
            } else {
                // 回退路径已在 EventStore 层补 CAS（updateMetadataCAS），但快照+事件仍非同一事务
                if (!skipES) {
                    appendToStream(entity, streamName);
                } else {
                    entity.markEventsPersisted(entity.getPendingEvents());
                }
                saveSnapshot(entity, FuncType.modify);
                entity.markStored();
            }
        }
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
        if (BatchRepository.capture(entity, FuncType.delete, this)) return;
        if (entity.isStored()) return;
        String streamName = streamNameFor(entity.getClass(), entity.getId());
        boolean skipES = isSkipEventSourcing(entity);
        if (SessionManager.contains(entity)) {
            ISession session = SessionManager.Get(entity);
            if (session instanceof DomainSession) {
                DomainSession ds = (DomainSession) session;
                Integer expected = entity.startVersion == 0 ? null : entity.startVersion;
                BatchOperation op = new BatchOperation(
                        BatchOperation.Type.DELETE, entity, null, null, expected, skipES, true);
                ds.addOperation(op);
            } else {
                session.setSessionFunction(() -> {
                    validateEntity(entity, FuncType.delete);
                    if (trashRepository != null)
                        trashRepository.saveToTrash(entity.getClass(), streamName, entity.getId());
                    deleteSnapshot(entity);
                    if (!skipES) {
                        invalidStream(entity, streamName);
                    } else {
                        entity.markEventsPersisted(entity.getPendingEvents());
                    }
                    entity.markStored();
                });
            }
        } else {
            Integer expected = entity.startVersion == 0 ? null : entity.startVersion;
            if (tryAtomicSingle(com.sooncode.project.core.batcher.BatchOperation.Type.DELETE, entity, null, skipES, expected)) {
            } else {
                // 回退路径：trash+快照删除+事件失效非事务，delete 的 CAS 已在 invalid() 中补偿
                validateEntity(entity, FuncType.delete);
                if (!skipES) {
                    invalidStream(entity, streamName);
                } else {
                    entity.markEventsPersisted(entity.getPendingEvents());
                }
                if (trashRepository != null)
                    trashRepository.saveToTrash(entity.getClass(), streamName, entity.getId());
                deleteSnapshot(entity);
                entity.markStored();
            }
        }
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
     * 直接保存快照（接口兼容方法），按添加语义做领域校验。
     * 注意：修改场景请走 save()，它会按修改语义校验；直接调本方法保存修改后的实体会用错校验语义。
     * TODO(历史遗留)：快照先于事件流写入，事件 append 失败时快照会超前；
     * 成功路径依赖 markEventsPersisted 边界做重试，失败重试时事件可重发但快照已更新。
     *
     * @param entity 实体
     */
    @Override
    public void saveSnapshot(Entity entity) {
        saveSnapshot(entity, FuncType.add);
    }

    private void saveSnapshot(Entity entity, FuncType funcType) {
        validateEntity(entity, funcType);
        String id = streamNameFor(entity.getClass(), entity.getId());
        eventStore.saveSnapshot(id, entity);
    }

    /**
     * 直接删除快照（接口兼容方法）。删除语义的领域校验由 {@code delete()} 入口负责，
     * 直接调用本方法请自行先按删除语义校验，方法内不再重复校验。
     *
     * @param entity 实体
     */
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

    /**
     * 单条原子提交：通过批量事务保证快照+事件+元数据同一事务且带版本 CAS。
     * 成功返回 true（已由批量层完成 markEventsPersisted/markStored），失败抛异常。
     * 无批量存储时返回 false 走回退路径。
     */
    private boolean tryAtomicSingle(com.sooncode.project.core.batcher.BatchOperation.Type type, T entity, T oldEntity, boolean skipES) {
        return tryAtomicSingle(type, entity, oldEntity, skipES, entity.startVersion == 0 ? null : entity.startVersion);
    }

    private boolean tryAtomicSingle(com.sooncode.project.core.batcher.BatchOperation.Type type, T entity, T oldEntity, boolean skipES, Integer expectedVersion) {
        if (Monitor.instance == null) return false;
        com.sooncode.project.core.batcher.BatchRepository<?> batchRepo = Monitor.instance.getBatchRepository();
        if (batchRepo == null || batchRepo.getBatchStore() == null) return false;
        // 对于 MODIFY 需要 oldEntity 校验
        if (type == com.sooncode.project.core.batcher.BatchOperation.Type.MODIFY && oldEntity == null) {
            oldEntity = findByID(entity.getId(), (Class<T>) entity.getClass());
        }
        com.sooncode.project.core.batcher.BatchOperation op = new com.sooncode.project.core.batcher.BatchOperation(
                type, entity, oldEntity, null, expectedVersion, skipES, trashRepository != null && trashRepository.isEnabled());
        com.sooncode.project.core.batcher.BatchOptions opts = new com.sooncode.project.core.batcher.BatchOptions().atomic(com.sooncode.project.core.config.InfraConfig.isAtomic()).failureMode(com.sooncode.project.core.batcher.FailureMode.FAIL_FAST).monitor(false);
        // 批量仓储内部会做校验与事件快照准备，成功后已推进事件边界
        batchRepo.persistOperations(Collections.singletonList(op), opts);
        // 成功后同步 startVersion 到新版本，保持会话外乐观锁基准
        entity.startVersion = entity.getVersion();
        // 触发监听（单条原本会 Notice）
        return true;
    }

    private Integer getExpectedVersion(int startVersion) {
        return startVersion == 0 ? null : startVersion;
    }

    private String streamNameFor(Class c, String id) {
        return String.format("%s-%s", c.getName(), id);
    }
    private void createNewStream(DomainModel<T> entity,String streamName) {
        List<DomainEvent> events = prepareEventSnapshots(entity);
        // 修复：新增必须先创建元数据，之前错误地直接 append 导致“没有找到元数据”
        eventStore.createNewStream(streamName, events, (Class<T>) entity.getClass());
        entity.markEventsPersisted(events);
    }
    private void appendToStream(DomainModel<T> entity,String streamName) {
        List<DomainEvent> events = prepareEventSnapshots(entity);
        eventStore.appendEventToStream(streamName, events, getExpectedVersion(entity.startVersion), (Class<T>) entity.getClass());
        entity.markEventsPersisted(events);
    }
    private void invalidStream(DomainModel<T> entity, String streamName) {
        List<DomainEvent> events = prepareEventSnapshots(entity);
        eventStore.invalid(streamName, events, getExpectedVersion(entity.startVersion), (Class<T>) entity.getClass());
        entity.markEventsPersisted(events);
    }
    private void validateEntity(Entity entity, FuncType funcType) {
        if (entity instanceof IValidate) {
            IValidate validate = (IValidate) entity;
            ModelValidateFailException exception = validate.validate(funcType);
            if (exception != null)
                throw exception;
        }
    }
    /**
     * 为本次待写入事件准备最终实体审计快照，不改变事件定义参数（实字段与动态参数保持发生时数据）。
     * 必须在实体最终状态确定且领域校验通过后、事件真正写入前调用。
     */
    public List<DomainEvent> prepareEventSnapshots(DomainModel<T> entity) {
        return entity.preparePendingEventSnapshots();
    }

    /**
     * 历史方法名，语义同 {@link #prepareEventSnapshots}：只生成审计快照，不转换事件参数。
     * 保留仅为兼容，名字有误导性，新代码请用 prepareEventSnapshots。
     */
    @Deprecated
    public List<DomainEvent> convertEventParam(DomainModel<T> entity) {
        return prepareEventSnapshots(entity);
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
