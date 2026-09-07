package com.sooncode.project.core.batcher;

import java.util.List;

/** 批量持久化存储接口。 */
public interface IBatchStore {
    /** 持久化已经由 BatchRepository 准备好的批量操作。 */
    void persistBatch(List<BatchOperation> operations, boolean atomic);
}
