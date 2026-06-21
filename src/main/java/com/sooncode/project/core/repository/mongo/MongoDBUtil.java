package com.sooncode.project.core.repository.mongo;


import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

import java.util.Collections;

class MongoDBUtil {
    /**
     * MongoDB连接对象
     */
    private MongoClient client;
    private String host;
    private int port;
    private String username;
    private String password;
    private String dbname="";
    private MongoDBUtil(){};
    public MongoDBUtil(String host,int port){
        this.host=host;
        this.port=port;
    }
    public MongoDBUtil(String host,int port,String username,String password){
        this.host=host;
        this.port=port;
        this.username=username;
        this.password=password;
    }
    /**
     * 关闭连接对象
     */
    public void closeDB(){
        if(client != null){
            client.close();
        }
        client = null;
    }

    /**
     * 不需要认证获取连接对象
     */
    public void mongoClient(){
        try {
            ServerAddress serverAddress = new ServerAddress(this.host,this.port);
            client = MongoClients.create(MongoClientSettings.builder().applyToClusterSettings(setting->{
                setting.hosts(Collections.singletonList(serverAddress));
            }).writeConcern(WriteConcern.MAJORITY).build());

        } catch (Exception e) {
            System.out.println("不需要认证获取连接对象失败,"+e.getMessage());
        }
    }

    /**
     * 需要认证获取连接对象
     */
    public void certifyMongoClient() {
        try {
            ServerAddress serverAddress = new ServerAddress(this.host, this.port);
            MongoCredential mongoCredential = MongoCredential.createCredential(this.username, "admin", this.password.toCharArray());
            client = MongoClients.create(MongoClientSettings.builder().credential(mongoCredential).applyToClusterSettings(setting -> {
                setting.hosts(Collections.singletonList(serverAddress));
            }).writeConcern(WriteConcern.MAJORITY).build());

        } catch (Exception e) {
            System.out.println("需要认证的获取连接对象失败," + e.getMessage());
        }
    }
    /**
     * 获取数据库对象
     * @param databaseName  数据库名
     * @return  MongoDatabase
     */
    public MongoDatabase getDatabase(String databaseName){
        if(client == null){
            if(username==null)
                mongoClient();
            else certifyMongoClient();
        }
        MongoDatabase database = client.getDatabase(databaseName);
        return database;
    }
}
