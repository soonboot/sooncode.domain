package com.sooncode.project.core.batcher;

import com.sooncode.project.core.model.DomainException;

/**
 * 批量持久化选项。
 */
public class BatchOptions {
    private boolean atomic = false;
    private boolean monitor = true;
    private FailureMode failureMode = FailureMode.CONTINUE;

    public boolean isAtomic() {
        return atomic;
    }

    public BatchOptions atomic(boolean atomic) {
        this.atomic = atomic;
        return this;
    }

    public boolean isMonitor() {
        return monitor;
    }

    public BatchOptions monitor(boolean monitor) {
        this.monitor = monitor;
        return this;
    }

    /**
     * 设置批量操作的失败处理策略。默认是 {@link FailureMode#CONTINUE}。
     */
    public BatchOptions failureMode(FailureMode failureMode) {
        this.failureMode = failureMode == null ? FailureMode.CONTINUE : failureMode;
        return this;
    }

    public FailureMode getFailureMode() {
        return failureMode;
    }

    public static BatchOptions defaults() {
        return new BatchOptions();
    }

    /**
     * 是否使用 MongoDB 事务。默认关闭；需要副本集或 MongoDB Atlas 才能开启。
     */
    public boolean atomic() {
        return atomic;
    }

    /**
     * 是否在批量提交成功后触发实体监听器。
     */
    public boolean monitor() {
        return monitor;
    }

    /**
     * 返回批量操作的失败处理策略。
     */
    public FailureMode failureMode() {
        return failureMode;
    }

    /**
     * 校验失败模式与事务模式的组合是否有效。
     */
    public void validate() {
        if (failureMode == FailureMode.CONTINUE && atomic) {
            throw new DomainException("failureMode=CONTINUE 时 atomic 必须为 false");
        }
    }
}
