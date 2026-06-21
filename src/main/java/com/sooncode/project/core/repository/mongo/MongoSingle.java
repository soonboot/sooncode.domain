package com.sooncode.project.core.repository.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.sooncode.project.core.model.IEventSourcingRepository;
import com.sooncode.project.core.monitor.Monitor;

public class MongoSingle {
    private MongoSingle(){};
    IMongoDBDao mongoDB;
    String dbName;
    MongoEventSourcingRepository repository;

    private static MongoSingle instance;
    public static MongoSingle getInstance(){
        return New();
    };
    public IEventSourcingRepository getRepository(){
        return repository;
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
        return mongoDB.getCollection(dbName,"eventSnapshot");
    }
}
