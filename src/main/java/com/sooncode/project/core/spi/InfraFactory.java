package com.sooncode.project.core.spi;

import com.sooncode.project.core.finder.IFindRepository;
import com.sooncode.project.core.batcher.IBatchRepository;
import com.sooncode.project.core.lookup.ILookupBulkWriter;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.IEventSourcingRepository;
import com.sooncode.project.core.trash.ITrashRepository;

/**
 * 基础设施抽象工厂（Abstract Factory）。
 *
 * <p>同一数据库的一整套仓储必须成套创建，避免 Mongo 的 Find 配 PG 的 Event 等混用。
 * 新增数据库只需新增一个实现（如 {@code PgFactory}），core 层零改动。
 *
 * <p>可通过 {@code META-INF/services/...InfraFactory} 做 SPI 注册，或显式
 * {@code InfraContext.of(new XxxFactory(...))} 注入。
 */
public interface InfraFactory {

    /** 事件溯源仓储（强一致主写链路）。 */
    IEventSourcingRepository events();

    /** 查询仓储（Strategy：各库内部持有自己的查询翻译器）。 */
    <T extends DomainModel<T>> IFindRepository<T> finds(Class<T> cla);

    /**
     * 批量仓储（可选能力）。不支持的库返回 {@code null}，
     * 调用方需回退为逐条写入。
     */
    default IBatchRepository batch() {
        return null;
    }

    /** 回收站仓储。 */
    ITrashRepository trash();

    /**
     * Lookup 反范式批量写入器（BEST_EFFORT，最终一致）。
     *
     * @param sourceRepo 事件溯源仓储，用于需要直读快照的场景；实现可忽略
     */
    ILookupBulkWriter lookupWriter(IEventSourcingRepository sourceRepo);

    /** 建表/建索引等自举逻辑，默认空实现。 */
    default void initSchema() {
    }

    /** 工厂名称，用于日志与排查，如 {@code "mongo"/"pgsql"}。 */
    default String name() {
        return getClass().getSimpleName();
    }
}
