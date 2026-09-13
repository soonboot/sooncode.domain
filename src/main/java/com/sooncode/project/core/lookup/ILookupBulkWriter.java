package com.sooncode.project.core.lookup;

import com.sooncode.project.core.model.Entity;
import java.util.List;

/**
 * Lookup 专用的批量快照写入器（BEST_EFFORT）。
 * <p>
 * 与业务批量 {@code Batcher/IBatchRepository} 彻底解耦：
 * <ul>
 *   <li>业务批量：要走 validate / CAS / 事件流 / 事务 / Notice，强一致。</li>
 *   <li>Lookup 批量：只做快照表的 {@code ReplaceOne upsert ordered:false}，最终一致、失败仅日志。</li>
 * </ul>
 * 混用会导致 Lookup 的 BEST_EFFORT 污染业务事务，或业务批的校验/事件语义被 Lookup 绕过。
 */
public interface ILookupBulkWriter {
    /**
     * 批量保存快照（按 ReplaceOne upsert）。
     *
     * @param modelType 快照所属的模型类型，用于解析 {@code @ModelSnapshot} 集合名
     * @param entities  已在内存中完成字段回填的脏实体列表（不会为空时已做去重/过滤）
     * @return 成功写入条数（BulkWrite 异常时为部分成功计数；全量 fallback 时为逐条成功数）
     */
    int bulkSaveSnapshots(Class<?> modelType, List<Entity> entities);

    /**
     * 刷新批量写入器的动态配置（如 bulkBatchSize）。默认空实现；支持动态配置的实现应覆盖。
     * 由 {@code LookupHandler.refreshConfig()} 调用（P1-5）。
     */
    default void refresh() {}
}
