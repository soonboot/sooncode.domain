package com.sooncode.project.core.batcher;

/**
 * 批量操作的失败处理策略。
 */
public enum FailureMode {
    /** 单个操作失败后记录失败并继续执行后续操作。 */
    CONTINUE,
    /** 任意操作失败立即抛出异常，批量操作不再继续。 */
    FAIL_FAST
}
