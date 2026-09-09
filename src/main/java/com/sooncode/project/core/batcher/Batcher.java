package com.sooncode.project.core.batcher;

import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.IDomainRepository;
import com.sooncode.project.core.monitor.FuncType;
import com.sooncode.project.core.monitor.Monitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 领域批量操作门面。
 *
 * Batcher 同时支持显式收集和领域行为收集：
 *
 * Batcher<User> batcher = new Batcher<>(User.class);
 * batcher.run({
 *     user1.changeName("A");
 *     user2.changeName("B");
 * });
 * BatchResult<User> result = batcher.execute();
 *
 * 在 run 作用域内，DomainModel 产生的事件不会触发单条持久化，
 * 而是收集到当前 Batcher 中，execute 时一次性提交。
 */
public class Batcher<T extends DomainModel> implements IBatcher<T> {
    private static final ThreadLocal<Batcher<?>> CURRENT = new ThreadLocal<>();

    private Class<T> entityClass;
    private final IDomainRepository<T> repository;
    private final BatchRepository<T> batchRepository;
    private final List<BatchOperation> operations = new ArrayList<>();
    private final Map<DomainModel, BatchOperation.Type> operationTypes = new IdentityHashMap<>();
    private final Map<String, BatchOperation.Type> operationKeys = new java.util.HashMap<>();
    private BatchOptions options = BatchOptions.defaults();
    private int skippedCount;

    public Batcher(Class<T> entityClass) {
        this(entityClass, repositoryFromMonitor());
    }

    /**
     * 创建一个不预设实体类型的批量作用域。该构造器主要供
     * 供不预设实体类型的领域批量作用域使用；实体类型会在第一次收集操作时校验。
     */
    public Batcher(IDomainRepository<T> repository) {
        this(null, repository);
    }

    public Batcher(Class<T> entityClass, IDomainRepository<T> repository) {
        if (repository == null) throw new DomainException("批量操作仓库未配置");
        this.entityClass = entityClass;
        this.repository = repository;
        if (Monitor.instance != null
                && Monitor.instance.getDomainRepository() == repository
                && Monitor.instance.getBatchRepository() != null) {
            this.batchRepository = (BatchRepository<T>) Monitor.instance.getBatchRepository();
        } else {
            this.batchRepository = new BatchRepository<>(repository);
        }
    }

    private static <T extends DomainModel> IDomainRepository<T> repositoryFromMonitor() {
        if (Monitor.instance == null || Monitor.instance.getDomainRepository() == null) {
            throw new DomainException("批量操作仓库未配置");
        }
        return (IDomainRepository<T>) Monitor.instance.getDomainRepository();
    }

    /** 设置本 Batcher 的提交选项。 */
    public Batcher<T> options(BatchOptions options) {
        this.options = options == null ? BatchOptions.defaults() : options;
        return this;
    }

    public BatchOptions getOptions() {
        return options;
    }

    /** 显式收集已有事件的实体。 */
    public Batcher<T> add(T entity) {
        register(BatchOperation.Type.ADD, entity);
        return this;
    }

    public Batcher<T> addAll(Collection<T> entities) {
        if (entities != null) for (T entity : entities) add(entity);
        return this;
    }

    /** 显式收集已有事件的实体。 */
    public Batcher<T> save(T entity) {
        register(BatchOperation.Type.MODIFY, entity);
        return this;
    }

    public Batcher<T> saveAll(Collection<T> entities) {
        if (entities != null) for (T entity : entities) save(entity);
        return this;
    }

    /** 显式收集已有事件的实体。 */
    public Batcher<T> delete(T entity) {
        register(BatchOperation.Type.DELETE, entity);
        return this;
    }

    public Batcher<T> deleteAll(Collection<T> entities) {
        if (entities != null) for (T entity : entities) delete(entity);
        return this;
    }

    /**
     * 按 ID 收集删除操作。实体在 Batcher 作用域内生成标准删除事件，
     * 因此不依赖 Monitor.Store 是否已配置。
     */
    public Batcher<T> deleteByIds(Collection<String> ids) {
        if (ids == null) return this;
        if (entityClass == null) throw new DomainException("按 ID 批量删除必须提供实体类型");
        for (String id : ids) {
            if (id == null || id.isEmpty()) throw new DomainException("批量删除实体 ID 不能为空");
            T entity = repository.findByID(id, entityClass);
            if (entity == null) {
                skippedCount++;
                continue;
            }
            activate(() -> entity.delete());
            register(BatchOperation.Type.DELETE, entity);
        }
        return this;
    }

    /**
     * 在 Batcher 作用域内执行业务行为，领域事件会自动收集。
     */
    @Override
    public BatchResult<T> run(Consumer<? super IBatcher<T>> action) {
        if (action == null) throw new DomainException("批量作用域 action 不能为 null");
        activate(() -> action.accept(this));
        return execute();
    }

    /** 提交当前收集的所有操作，支持新增、修改、删除混合执行。 */
    public BatchResult<T> execute() {
        options.validate();
        if (operations.isEmpty()) {
            BatchResult<T> result = new BatchResult<>();
            for (int i = 0; i < skippedCount; i++) result.incrementSkipped();
            skippedCount = 0;
            eventNotices.clear();
            eventNoticeKeys.clear();
            return result;
        }
        List<BatchOperation> pending = new ArrayList<>();
        BatchResult<T> skipped = new BatchResult<>();
        for (BatchOperation operation : operations) {
            if (operation.getEntity().isStored()) skipped.incrementSkipped();
            else pending.add(operation);
        }
        if (pending.isEmpty()) {
            for (int i = 0; i < skippedCount; i++) skipped.incrementSkipped();
            skippedCount = 0;
            operations.clear();
            operationTypes.clear();
            operationKeys.clear();
            eventNotices.clear();
            eventNoticeKeys.clear();
            return skipped;
        }
        BatchResult<T> result;
        try {
            if (options.getFailureMode() == FailureMode.CONTINUE) {
                List<BatchOperation> succeededOperations = new ArrayList<>();
                result = executeContinue(pending, succeededOperations);
                // 领域事件通知必须在批量写入成功、实体标记为 stored 之后发送。
                for (int i = 0; i < skippedCount; i++) result.incrementSkipped();
                for (int i = 0; i < skipped.getSkippedCount(); i++) result.incrementSkipped();
                skippedCount = 0;
                operations.clear();
                operationTypes.clear();
                operationKeys.clear();
                flushEventNotices();
                notifyEntityOperations(succeededOperations);
                return result;
            } else {
                collectEntityEvents(pending);
                result = batchRepository.persistOperations(pending, options);
                // 领域事件通知必须在批量写入成功、实体标记为 stored 之后发送。
                for (int i = 0; i < skippedCount; i++) result.incrementSkipped();
                for (int i = 0; i < skipped.getSkippedCount(); i++) result.incrementSkipped();
                skippedCount = 0;
                operations.clear();
                operationTypes.clear();
                operationKeys.clear();
                // 领域事件通知必须在批量写入成功、实体标记为 stored 之后发送。
                flushEventNotices();
                notifyEntityOperations(pending);
                return result;
            }
        } catch (RuntimeException ex) {
            // 写入失败时保留队列，便于调用方修正后重试。
            throw ex;
        }
    }

    /**
     * CONTINUE 模式按操作逐项提交。每一项拥有独立的持久化边界，单项失败不会影响后续项。
     * 当 atomic=true 时，atomic 的语义是“每个操作原子”，而不是整个批次原子。
     */
    private BatchResult<T> executeContinue(List<BatchOperation> pending,
                                           List<BatchOperation> succeededOperations) {
        BatchResult<T> result = new BatchResult<>();
        for (BatchOperation operation : pending) {
            try {
                collectEntityEvents(java.util.Collections.singletonList(operation));
                BatchResult<T> one = persistOne(operation);
                result.merge(one);
                if (one.getSuccessCount() > 0) {
                    succeededOperations.add(operation);
                } else {
                    discardEventNotices(operation.getEntity());
                }
            } catch (RuntimeException ex) {
                discardEventNotices(operation.getEntity());
                result.addFailure((T) operation.getEntity(), operation.getType(), ex);
            }
        }
        return result;
    }

    private BatchResult<T> persistOne(BatchOperation operation) {
        List<BatchOperation> one = java.util.Collections.singletonList(operation);
        return batchRepository.persistOperations(one, options);
    }

    private void collectEntityEvents(List<BatchOperation> pending) {
        for (BatchOperation operation : pending) {
            for (Object event : operation.getEntity().getEvents()) {
                deferEventNotice((DomainEvent) event, operation.getEntity());
            }
        }
    }

    private void notifyEntityOperations(List<BatchOperation> pending) {
        if (!options.isMonitor() || Monitor.instance == null) return;
        for (BatchOperation operation : pending) {
            DomainModel entity = operation.getEntity();
            if (operation.getType() == BatchOperation.Type.ADD) {
                Monitor.instance.Notice(entity, FuncType.add);
            } else if (operation.getType() == BatchOperation.Type.MODIFY) {
                Monitor.instance.Notice(entity, operation.getOldEntity(), FuncType.modify);
            } else {
                Monitor.instance.Notice(entity, FuncType.delete);
            }
        }
    }

    private void discardEventNotices(DomainModel entity) {
        eventNotices.removeIf(notice -> notice.entity == entity);
        eventNoticeKeys.entrySet().removeIf(entry -> entry.getValue() == entity);
    }

    /** 被 StoreNotice 调用，将自动存储事件转为当前 Batcher 的操作。 */
    public static boolean capture(DomainModel entity, FuncType funcType, IDomainRepository repository) {
        Batcher<?> batcher = CURRENT.get();
        if (batcher == null || batcher.repository != repository) return false;
        if (com.sooncode.project.core.session.SessionManager.contains(entity)) {
            throw new DomainException("批量 Batcher 与 DomainSession 不能在同一实体上混用: " + entity.getId() + "，请二选一");
        }
        if (batcher.entityClass != null && !batcher.entityClass.isAssignableFrom(entity.getClass())) return false;
        BatchOperation.Type type;
        switch (funcType) {
            case add: type = BatchOperation.Type.ADD; break;
            case modify: type = BatchOperation.Type.MODIFY; break;
            case delete: type = BatchOperation.Type.DELETE; break;
            default: return false;
        }
        batcher.register(type, entity);
        return true;
    }

    /** 供 DomainModel 在没有 Monitor/StoreNotice 时也能收集领域操作。 */
    public static boolean capture(DomainModel entity, FuncType funcType) {
        Batcher<?> batcher = CURRENT.get();
        if (batcher == null) return false;
        if (com.sooncode.project.core.session.SessionManager.contains(entity)) {
            throw new DomainException("批量 Batcher 与 DomainSession 不能在同一实体上混用: " + entity.getId() + "，请二选一");
        }
        if (batcher.entityClass != null && !batcher.entityClass.isAssignableFrom(entity.getClass())) return false;
        BatchOperation.Type type = typeOf(funcType);
        if (type == null) return false;
        batcher.register(type, entity);
        return true;
    }

    /** 供 DomainRepository 的显式单条 API 收集没有伴随事件的操作。 */
    public static boolean captureOperation(DomainModel entity, BatchOperation.Type type,
                                    IDomainRepository repository) {
        Batcher<?> batcher = CURRENT.get();
        if (batcher == null || batcher.repository != repository) return false;
        if (batcher.entityClass != null && !batcher.entityClass.isAssignableFrom(entity.getClass())) return false;
        batcher.register(type, entity);
        return true;
    }

    public static Batcher<?> current() {
        return CURRENT.get();
    }

    public void deferEventNotice(DomainEvent event, DomainModel entity) {
        if (event != null && !eventNoticeKeys.containsKey(event)) {
            eventNoticeKeys.put(event, entity);
            eventNotices.add(new EventNotice(event, entity));
        }
    }

    private final List<EventNotice> eventNotices = new ArrayList<>();
    private final Map<DomainEvent, DomainModel> eventNoticeKeys = new IdentityHashMap<>();

    void flushEventNotices() {
        if (!options.isMonitor() || Monitor.instance == null) {
            eventNotices.clear();
            eventNoticeKeys.clear();
            return;
        }
        List<EventNotice> pending = new ArrayList<>(eventNotices);
        eventNotices.clear();
        eventNoticeKeys.clear();
        for (EventNotice notice : pending) {
            Monitor.instance.Notice(notice.event, notice.entity);
        }
    }

    private void register(BatchOperation.Type type, DomainModel entity) {
        if (entity == null) throw new DomainException("批量操作实体不能为 null");
        if (entityClass == null) {
            entityClass = (Class<T>) entity.getClass();
        } else if (!entityClass.isAssignableFrom(entity.getClass())) {
            throw new DomainException("批量操作实体类型不匹配:" + entity.getClass().getName());
        }
        BatchOperation.Type oldType = operationTypes.get(entity);
        if (oldType != null) {
            if (oldType != type) throw new DomainException("同一批量中同一实体不能混用操作类型:" + entity.getId());
            return;
        }
        String operationKey = entity.getClass().getName() + "-" + entity.getId();
        if (operationKeys.containsKey(operationKey)) {
            throw new DomainException("同一批量中实体 ID 不能重复:" + entity.getId());
        }
        operationTypes.put(entity, type);
        operationKeys.put(operationKey, type);
        // oldEntity 延迟到 BatchRepository.persistWithBatchStore 再查询，避免收集期同步读
        DomainModel oldEntity = null;
        // 不在收集时复制 events。一个实体在同一作用域内可能连续产生多个事件，
        // 提交时读取 entity.events 才能保证事件完整。
        operations.add(new BatchOperation(type, entity, oldEntity, null,
                type == BatchOperation.Type.ADD || entity.startVersion == 0 ? null : entity.startVersion,
                false, true));
    }

    private static BatchOperation.Type typeOf(FuncType funcType) {
        if (funcType == null) return null;
        switch (funcType) {
            case add: return BatchOperation.Type.ADD;
            case modify: return BatchOperation.Type.MODIFY;
            case delete: return BatchOperation.Type.DELETE;
            default: return null;
        }
    }

    private void activate(Runnable action) {
        Batcher<?> previous = CURRENT.get();
        if (previous != null && previous != this) throw new DomainException("不支持嵌套批量作用域");
        CURRENT.set(this);
        try {
            action.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    private static final class EventNotice {
        private final DomainEvent event;
        private final DomainModel entity;

        private EventNotice(DomainEvent event, DomainModel entity) {
            this.event = event;
            this.entity = entity;
        }
    }
}
