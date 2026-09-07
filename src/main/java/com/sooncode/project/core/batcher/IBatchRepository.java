package com.sooncode.project.core.batcher;

import java.util.List;

/**
 * 批量持久化仓储接口。
 *
 * <p>该接口与单事件的 {@code IEventSourcingRepository} 分离，
 * 具体数据库可以独立提供批量写入、事务以及 bulk 优化。</p>
 */
public interface IBatchRepository {
    void persistBatch(List<BatchOperation> operations, boolean atomic);
}
