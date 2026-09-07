package com.sooncode.project.core.batcher;

import com.sooncode.project.core.model.DomainException;

import java.util.List;

/**
 * 事件溯源仓储的批量存储适配器。
 *
 * <p>批量入口位于 batcher 包，底层事件溯源仓储只负责实际存储实现。</p>
 */
public class BatchStore implements IBatchStore {
    private final IBatchRepository repository;

    public BatchStore(IBatchRepository repository) {
        if (repository == null) throw new DomainException("批量存储仓储未配置");
        this.repository = repository;
    }

    @Override
    public void persistBatch(List<BatchOperation> operations, boolean atomic) {
        repository.persistBatch(operations, atomic);
    }
}
