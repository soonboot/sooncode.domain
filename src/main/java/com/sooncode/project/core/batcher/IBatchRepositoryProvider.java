package com.sooncode.project.core.batcher;

/**
 * 批量仓储能力提供者。
 *
 * <p>数据库连接是否支持批量操作属于可选能力，不应成为基础连接接口的职责。</p>
 */
public interface IBatchRepositoryProvider {
    IBatchRepository getBatchRepository();
}
