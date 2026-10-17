package com.sooncode.project.core.repository.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.sooncode.project.core.model.IEventSourcingRepository;
import com.sooncode.project.core.batcher.IBatchRepository;
import com.sooncode.project.core.monitor.Monitor;

/**
 * 兼容垫片（已废弃）：全局 Service Locator，仅为存量代码保留。
 * 新代码请使用 {@link com.sooncode.project.core.spi.InfraContext} + {@link com.sooncode.project.core.spi.InfraFactory}
 * 显式注入；双库并存时不要依赖本单例。本类行为保持不变，不会删除。
 *
 * @deprecated 使用 InfraContext/InfraFactory 替代，本类仅做兼容转发
 */
@Deprecated
public class MongoSingle {
    private MongoSingle(){};
    IMongoDBDao mongoDB;
    String dbName;
    MongoEventSourcingRepository repository;
    IBatchRepository batchRepository;

    private static MongoSingle instance;
    public static MongoSingle getInstance(){
        return New();
    };
    public IEventSourcingRepository getRepository(){
        return repository;
    }

    public IBatchRepository getBatchRepository() {
        return batchRepository;
    }
    public static MongoSingle New(){
        instance= MongoSingle.Singleton.INSTANCE.getInstance();
        return instance;
    }
    private enum Singleton {
        INSTANCE;
        private MongoSingle instance;
        Singleton() {
            instance = new MongoSingle();
        }
        public MongoSingle getInstance() {
            return instance;
        }
    }
    public MongoDatabase getMongoDB(){
        return mongoDB.getDb(dbName);
    }
    public MongoCollection getSnapshotCollection(){
        return mongoDB.getCollection(dbName, com.sooncode.project.core.repository.StoreNames.MONGO_EVENT_SNAPSHOT);
    }
}
