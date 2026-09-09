package com.sooncode.project.core.batcher;

import com.sooncode.project.core.model.DomainException;

/**
 * 批量持久化选项。
 */
public class BatchOptions {
    private boolean atomic = true;
    /**
     * 是否在批量提交成功后触发 Monitor 监听（实体监听 + 领域事件）。
     * {@code true}（默认）：批量提交成功后按 {@link com.sooncode.project.core.monitor.Monitor} 语义触发
     * {@code ListenEntity}/{@code ListenEvent}，与单条 {@code repository.add/save/delete} 保持一致。
     * {@code false}：静默写入，仅做 {@code bulkWrite/insertMany}，不触发任何监听，适用于数据修复/ETL/种子数据等
     * 明确不需要副作用的场景。此时需调用方自行处理报表/投影的一致性。
     */
    private boolean monitor = true;
    private FailureMode failureMode = FailureMode.CONTINUE;

    public boolean isAtomic() {
        return atomic;
    }

    public BatchOptions atomic(boolean atomic) {
        this.atomic = atomic;
        return this;
    }

    /**
     * 是否触发监听。默认 {@code true}。
     * @see #monitor(boolean)
     * @see #silent(boolean)
     */
    public boolean isMonitor() {
        return monitor;
    }

    /**
     * 设置是否触发监听。
     * @param monitor {@code true} 触发 {@code Monitor}，{@code false} 静默写入
     */
    public BatchOptions monitor(boolean monitor) {
        this.monitor = monitor;
        return this;
    }

    /**
     * 是否触发监听（语义化别名，等价于 {@link #isMonitor()}）。
     */
    public boolean isNotifyListeners() {
        return monitor;
    }

    /**
     * 语义化别名：是否触发监听，等价于 {@link #monitor(boolean)}。
     */
    public BatchOptions notifyListeners(boolean notify) {
        return monitor(notify);
    }

    /**
     * 是否静默写入（不触发监听），与 {@link #monitor(boolean)} 互为反义。
     * @param silent {@code true} 静默（不触发），{@code false} 触发
     */
    public BatchOptions silent(boolean silent) {
        return monitor(!silent);
    }

    /**
     * 是否为静默模式，等价于 {@code !isMonitor()}。
     */
    public boolean isSilent() {
        return !monitor;
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
        // 读取全局事务开关：单机 Mongo（非副本集）需在启动时设为 false，否则批量事务会在 withTransaction 抛 replica set 异常
        return new BatchOptions().atomic(com.sooncode.project.core.config.InfraConfig.isAtomic());
    }

    /**
     * 是否使用 MongoDB 事务。默认为开启；需要副本集或 MongoDB Atlas，未配置副本集时请显式置为 false。
     */
    public boolean atomic() {
        return atomic;
    }

    /**
     * 是否在批量提交成功后触发实体监听器（等价于 {@link #isMonitor()}）。
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
     * <p>
     * 允许三种组合：
     * <ul>
     *   <li>{@code FAIL_FAST + atomic=true}：整批一个事务，要么全成功要么全回滚。</li>
     *   <li>{@code CONTINUE + atomic=true}：逐条事务，每条的快照/事件/元数据在独立事务内原子提交，单条失败不影响后续。</li>
     *   <li>{@code atomic=false}：无事务，仅靠 bulkWrite + 唯一索引兜底，可能出现快照与事件不一致。</li>
     * </ul>
     * </p>
     */
    public void validate() {
        // 保留空校验以兼容旧调用；所有 atomic/failureMode 组合现均合法，语义由 Batcher 区分：
        // CONTINUE+atomic 在 Batcher.executeContinue 中会拆为单条 persistOperations(singleton, atomic=true) 实现逐条原子。
    }
}
