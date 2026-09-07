package com.sooncode.project.core.batcher;

import com.sooncode.project.core.model.DomainModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 批量操作结果。
 */
public class BatchResult<T extends DomainModel> {
    private int successCount;
    private int skippedCount;
    private final List<BatchFailure<T>> failures = new ArrayList<>();

    public int getSuccessCount() {
        return successCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public int getFailureCount() {
        return failures.size();
    }

    /** 返回失败项的只读列表。 */
    public List<BatchFailure<T>> getFailures() {
        return Collections.unmodifiableList(failures);
    }

    public int getTotalCount() {
        return successCount + skippedCount + failures.size();
    }

    public void incrementSuccess() {
        successCount++;
    }

    public void incrementSkipped() {
        skippedCount++;
    }

    public void addFailure(T entity, BatchOperation.Type operationType, RuntimeException exception) {
        failures.add(new BatchFailure<>(entity, operationType, exception));
    }

    void merge(BatchResult<T> other) {
        if (other == null) return;
        for (int i = 0; i < other.successCount; i++) incrementSuccess();
        for (int i = 0; i < other.skippedCount; i++) incrementSkipped();
        failures.addAll(other.failures);
    }

}
