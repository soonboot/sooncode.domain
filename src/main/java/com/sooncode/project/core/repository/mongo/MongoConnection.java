package com.sooncode.project.core.repository.mongo;

import com.sooncode.project.core.model.IDBConnection;
import com.sooncode.project.core.model.IEventSourcingRepository;
import com.sooncode.project.core.batcher.IBatchRepository;
import com.sooncode.project.core.batcher.IBatchRepositoryProvider;
import com.mongodb.ConnectionString;

public class MongoConnection implements IDBConnection, IBatchRepositoryProvider {
    private IMongoDBDao dao;
    private String dbName;
    public MongoConnection(String connectionString){
        if (connectionString == null || connectionString.trim().isEmpty()) {
            throw new IllegalArgumentException("MongoDB connection string cannot be empty");
        }
        ConnectionString parsed = new ConnectionString(connectionString);
        if (parsed.getDatabase() == null || parsed.getDatabase().trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "MongoDB connection string must contain a database name, for example: "
                            + "mongodb://localhost:27017/myDatabase");
        }
        this.dao = new MongoDBImpl(connectionString);
        this.dbName = parsed.getDatabase();
        setInstance(new MongoEventSourcingRepository(this.dao, this.dbName));
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
        MongoIndexInitializer.initializeCoreIndexes(this.dao, this.dbName);
        MongoSingle.getInstance().mongoDB = this.dao;
        MongoSingle.getInstance().dbName = this.dbName;
        MongoSingle.getInstance().repository = repository;
        MongoSingle.getInstance().batchRepository = new BatchRepository(this.dao, this.dbName);
    }
    public IEventSourcingRepository getRepository(){
        return MongoSingle.getInstance().repository;
    }

    @Override
    public IBatchRepository getBatchRepository() {
        return MongoSingle.getInstance().getBatchRepository();
    }
}
