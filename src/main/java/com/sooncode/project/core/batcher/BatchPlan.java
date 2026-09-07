package com.sooncode.project.core.batcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 批量操作计划。
 *
 * <p>计划只描述操作语义，不包含任何数据库对象。各列表保持操作在原始
 * 批次中的相对顺序，并且在创建后不可修改。</p>
 */
public final class BatchPlan {
    private final List<BatchOperation> adds;
    private final List<BatchOperation> modifies;
    private final List<BatchOperation> deletes;

    BatchPlan(List<BatchOperation> adds, List<BatchOperation> modifies,
              List<BatchOperation> deletes) {
        this.adds = immutableCopy(adds);
        this.modifies = immutableCopy(modifies);
        this.deletes = immutableCopy(deletes);
    }

    public List<BatchOperation> getAdds() {
        return adds;
    }

    public List<BatchOperation> getModifies() {
        return modifies;
    }

    public List<BatchOperation> getDeletes() {
        return deletes;
    }

    public int size() {
        return adds.size() + modifies.size() + deletes.size();
    }

    private static List<BatchOperation> immutableCopy(List<BatchOperation> operations) {
        return Collections.unmodifiableList(new ArrayList<>(operations));
    }
}
