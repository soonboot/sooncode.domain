package com.sooncode.project.core.batcher;

import com.sooncode.project.core.model.DomainModel;

/**
 * 批量操作中的单项失败信息。
 */
public final class BatchFailure<T extends DomainModel> {
    private final T entity;
    private final BatchOperation.Type operationType;
    private final RuntimeException exception;

    public BatchFailure(T entity, BatchOperation.Type operationType, RuntimeException exception) {
        this.entity = entity;
        this.operationType = operationType;
        this.exception = exception;
    }

    public T getEntity() {
        return entity;
    }

    public BatchOperation.Type getOperationType() {
        return operationType;
    }

    public RuntimeException getException() {
        return exception;
    }

    public String getMessage() {
        return exception == null ? null : exception.getMessage();
    }
}
