package com.sooncode.project.core.repository.mongo;

import com.sooncode.project.core.model.IDBConnection;
import com.sooncode.project.core.model.IDomainRepository;
import com.sooncode.project.core.model.IEventSourcingRepository;

public class MongoConnection implements IDBConnection {
    private IMongoDBDao dao;
    private String dbName;
    public MongoConnection(String connectionString){

    }
    public  MongoConnection(String host,int port,String databaseName){
        this.dao = new MongoDBImpl(host, port);
        this.dbName = databaseName;
        setInstance(new MongoEventSourcingRepository(this.dao, this.dbName));
    }
    public MongoConnection(String host,int port,String databaseName,String username,String password){
        this.dao = new MongoDBImpl(host, port,username,password);
        this.dbName = databaseName;
        setInstance(new MongoEventSourcingRepository(this.dao, this.dbName));
    }
    MongoConnection(String host,int port,String databaseName,MongoEventSourcingRepository repository){
        this.dao = new MongoDBImpl(host, port);
        this.dbName = databaseName;
        setInstance(repository);
    }
    MongoConnection(String host,int port,String databaseName,String username,String password,MongoEventSourcingRepository repository){
        this.dao = new MongoDBImpl(host, port,username,password);
        this.dbName = databaseName;
        setInstance(repository);
    }

    private void setInstance(MongoEventSourcingRepository repository) {
        MongoSingle.getInstance().mongoDB = this.dao;
        MongoSingle.getInstance().dbName = this.dbName;
        MongoSingle.getInstance().repository = repository;
    }
    public IEventSourcingRepository getRepository(){
        return MongoSingle.getInstance().repository;
    }
}
