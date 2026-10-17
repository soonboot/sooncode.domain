package com.sooncode.project.core.repository.pgsql;

import com.sooncode.project.core.batcher.IBatchRepository;
import com.sooncode.project.core.finder.IFindRepository;
import com.sooncode.project.core.lookup.ILookupBulkWriter;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.IEventSourcingRepository;
import com.sooncode.project.core.spi.InfraFactory;
import com.sooncode.project.core.trash.ITrashRepository;

/**
 * PostgreSQL 整套仓储工厂（Abstract Factory 的 PGSQL 实现，对标 {@code MongoFactory}）。
 *
 * <p>与 {@code PgConnection} 共享同一个 {@code PgDao}，成套产出
 * Event/Find/Batch/Trash/LookupWriter，保证不会混用别库组件。
 * PG 当文档数据库用（JSONB 行 + 快照表），行为与 mongo 包逐项镜像。
 */
public class PgFactory implements InfraFactory {

    private final PgDao dao;

    public PgFactory(PgDao dao) {
        if (dao == null) {
            throw new IllegalArgumentException("PgFactory dao 不能为空");
        }
        this.dao = dao;
    }

    public PgDao dao() {
        return dao;
    }

    @Override
    public IEventSourcingRepository events() {
        return new PgEventSourcingRepository(dao);
    }

    @Override
    public <T extends DomainModel<T>> IFindRepository<T> finds(Class<T> cla) {
        return new PgFindRepository<>(cla, dao);
    }

    @Override
    public IBatchRepository batch() {
        return new PgBatchRepository(dao);
    }

    @Override
    public ITrashRepository trash() {
        return new PgTrashRepository(dao);
    }

    @Override
    public ILookupBulkWriter lookupWriter(IEventSourcingRepository sourceRepo) {
        int bulkBatchSize;
        try {
            bulkBatchSize = com.sooncode.project.core.config.InfraConfig.getLookupBulkBatchSize();
        } catch (Exception e) {
            bulkBatchSize = 500;
        }
        if (bulkBatchSize <= 0) {
            bulkBatchSize = 500;
        }
        return new PgLookupBulkWriter(dao, bulkBatchSize);
    }

    @Override
    public void initSchema() {
        dao.initSchema();
    }

    @Override
    public String name() {
        return "pgsql";
    }
}
