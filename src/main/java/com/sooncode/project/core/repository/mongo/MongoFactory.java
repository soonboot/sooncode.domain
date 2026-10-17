package com.sooncode.project.core.repository.mongo;

import com.sooncode.project.core.batcher.IBatchRepository;
import com.sooncode.project.core.finder.IFindRepository;
import com.sooncode.project.core.lookup.ILookupBulkWriter;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.IEventSourcingRepository;
import com.sooncode.project.core.spi.InfraFactory;
import com.sooncode.project.core.trash.ITrashRepository;

/**
 * Mongo 整套仓储工厂（Abstract Factory 的 Mongo 实现）。
 *
 * <p>与 {@code MongoConnection} 共享同一 {@code IMongoDBDao + dbName}，
 * 成套产出 Event/Find/Batch/Trash/LookupWriter，保证不会混用别库组件。
 */
public class MongoFactory implements InfraFactory {

    private final IMongoDBDao dao;
    private final String dbName;

    public MongoFactory(IMongoDBDao dao, String dbName) {
        if (dao == null) {
            throw new IllegalArgumentException("MongoFactory dao 不能为空");
        }
        if (dbName == null || dbName.trim().isEmpty()) {
            throw new IllegalArgumentException("MongoFactory dbName 不能为空");
        }
        this.dao = dao;
        this.dbName = dbName;
    }

    public IMongoDBDao dao() {
        return dao;
    }

    public String dbName() {
        return dbName;
    }

    @Override
    public IEventSourcingRepository events() {
        return new MongoEventSourcingRepository(dao, dbName);
    }

    @Override
    public <T extends DomainModel<T>> IFindRepository<T> finds(Class<T> cla) {
        return new FindRepository<>(cla, dao, dbName);
    }

    @Override
    public IBatchRepository batch() {
        return new BatchRepository(dao, dbName);
    }

    @Override
    public ITrashRepository trash() {
        return new MongoTrashRepository(dao, dbName);
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
        return new MongoLookupBulkWriter(dao, dbName, bulkBatchSize);
    }

    @Override
    public void initSchema() {
        MongoIndexInitializer.initializeCoreIndexes(dao, dbName);
    }

    @Override
    public String name() {
        return "mongo";
    }
}
