package com.sooncode.project.core.batcher;

import com.sooncode.project.core.annotations.SkipEventSourcing;
import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.Entity;
import com.sooncode.project.core.model.IDomainRepository;
import com.sooncode.project.core.model.IGenerateReport;
import com.sooncode.project.core.trash.Trash;
import com.sooncode.project.core.monitor.FuncType;
import com.sooncode.project.core.validator.IValidate;
import com.sooncode.project.core.validator.ModelValidateFailException;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量操作的统一持久化入口。
 *
 * <p>Batcher 负责收集和编排操作，BatchRepository 负责校验、准备以及提交操作。
 * DomainRepository 只保留单实体 CRUD，不再承担批量持久化职责。</p>
 */
public class BatchRepository<T extends DomainModel> {
    private final IDomainRepository<T> repository;
    private final IBatchStore batchStore;
    private Trash trashRepository;

    /** 创建一个基于普通领域仓储的批量仓储。 */
    public BatchRepository(IDomainRepository<T> repository) {
        this(repository, null, null);
    }

    /** 创建一个由指定批量存储器负责批量持久化的批量仓储。 */
    public BatchRepository(IDomainRepository<T> repository, IBatchStore batchStore,
                           Trash trashRepository) {
        if (repository == null) throw new DomainException("批量操作仓储未配置");
        this.repository = repository;
        this.batchStore = batchStore;
        this.trashRepository = trashRepository;
    }

    public void setTrashRepository(Trash trashRepository) {
        this.trashRepository = trashRepository;
    }

    public IBatchStore getBatchStore() {
        return batchStore;
    }

    /**
     * 供领域仓储在批量作用域内拦截单条 CRUD 调用。
     * 批量作用域状态和操作注册仍由 Batcher 管理，领域仓储不直接依赖其实现细节。
     */
    public static boolean capture(DomainModel entity, FuncType funcType,
                                  IDomainRepository<?> repository) {
        return Batcher.capture(entity, funcType, repository);
    }

    /**
     * 统一提交批量操作。
     *
     * <p>有统一批量存储器时：先做批量预处理（校验 + 待持久化事件审计快照），一次提交，
     * 成功后才推进事件边界，失败不推进以便重试。</p>
     *
     * <p>没有统一批量存储器时：逐项复用单实体 API，验证、审计快照与事件边界
     * 都由单实体 API 负责，这里不再预处理，避免重复验证和重复生成快照。</p>
     */
    public BatchResult<T> persistOperations(List<BatchOperation> operations, BatchOptions options) {
        if (operations == null || operations.isEmpty()) return new BatchResult<>();
        if (options == null) options = BatchOptions.defaults();
        options.validate();

        if (batchStore != null) {
            return persistWithBatchStore(operations, options);
        }

        // 没有统一事件存储器的自定义仓储，逐项复用单实体 API。
        // 允许单机非事务模式（atomic=false）下执行 Trash 删除：快照删除/回收站/元数据失效将以非事务批量提交，已由单条删除的非事务回退路径验证为可用
        BatchResult<T> result = new BatchResult<>();
        for (BatchOperation operation : operations) {
            if (operation == null) throw new DomainException("批量操作不能为 null");
            DomainModel entity = operation.getEntity();
            if (entity == null) throw new DomainException("批量操作实体不能为 null");
            if (entity.isStored()) {
                result.incrementSkipped();
                continue;
            }
            List<DomainEvent> pendingBefore = entity.getPendingEvents();
            persistOne(operation);
            // 标准 DomainRepository 会在实际写入后推进边界；自定义仓储若没有推进，
            // 这里补齐，避免下一次批量重复提交同一批事件。
            List<DomainEvent> stillPending = entity.getPendingEvents();
            if (sameEvents(stillPending, pendingBefore)) {
                entity.markEventsPersisted(stillPending);
            }
            entity.markStored();
            result.incrementSuccess();
        }
        return result;
    }

    /** 经由统一批量存储器提交：批量预处理后一次写入，成功后才推进事件边界。 */
    private BatchResult<T> persistWithBatchStore(List<BatchOperation> operations, BatchOptions options) {
        List<BatchOperation> prepared = new ArrayList<>();
        BatchResult<T> result = new BatchResult<>();
        for (BatchOperation operation : operations) {
            if (operation == null) throw new DomainException("批量操作不能为 null");
            DomainModel entity = operation.getEntity();
            if (entity == null) throw new DomainException("批量操作实体不能为 null");
            if (entity.isStored()) {
                result.incrementSkipped();
                continue;
            }

            DomainModel oldEntity = operation.getOldEntity();
            if (operation.getType() == BatchOperation.Type.MODIFY && oldEntity == null) {
                oldEntity = repository.findByID(entity.getId(), (Class<T>) entity.getClass());
            }
            validateEntity(entity, operation.getFuncType());
            List<DomainEvent> pendingEvents =
                    entity.preparePendingEventSnapshots();
            prepared.add(new BatchOperation(operation.getType(), entity, oldEntity,
                    pendingEvents, operation.getExpectedVersion(), isSkipEventSourcing(entity),
                    trashRepository != null && trashRepository.isEnabled()));
        }
        if (prepared.isEmpty()) return result;

        // 单机模式下（atomic=false）允许 Trash 删除：退化为非事务的 bulkWrite，虽无跨集合原子性但保持与单条删除的非事务回退路径一致
        batchStore.persistBatch(prepared, options.isAtomic());
        for (BatchOperation operation : prepared) {
            operation.getEntity().markEventsPersisted(operation.getEvents());
            operation.getEntity().markStored();
            // 同步乐观锁基准
            operation.getEntity().startVersion = operation.getEntity().getVersion();
            result.incrementSuccess();
        }
        return result;
    }

    private void persistOne(BatchOperation operation) {
        DomainModel entity = operation.getEntity();
        switch (operation.getType()) {
            case ADD:
                repository.add((T) entity, (IGenerateReport) null, false);
                break;
            case MODIFY:
                repository.save((T) entity, (IGenerateReport) null, false);
                break;
            case DELETE:
                repository.delete((T) entity, (IGenerateReport) null, false);
                break;
            default:
                throw new DomainException("未知批量操作类型:" + operation.getType());
        }
    }

    private void validateEntity(Entity entity, FuncType funcType) {
        if (entity instanceof IValidate) {
            ModelValidateFailException exception = ((IValidate) entity).validate(funcType);
            if (exception != null) throw exception;
        }
    }

    private boolean isSkipEventSourcing(DomainModel entity) {
        SkipEventSourcing annotation = entity.getClass().getAnnotation(SkipEventSourcing.class);
        return annotation != null && annotation.value();
    }

    private boolean sameEvents(List<DomainEvent> first,
                               List<DomainEvent> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            if (first.get(i) != second.get(i)) return false;
        }
        return true;
    }
}
