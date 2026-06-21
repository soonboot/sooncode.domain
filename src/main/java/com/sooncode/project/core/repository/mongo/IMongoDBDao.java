package com.sooncode.project.core.repository.mongo;


import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface IMongoDBDao {
    public enum SortEnum{
        ASC(1),
        DESC(-1);
        SortEnum(int value) {
        }
        public int value(){
            switch (this){
                case ASC:
                    return 1;
                case DESC:
                    return -1;
            }
            return 1;
        }

    }
    /**
     * 描述：获取指定的mongodb数据库
     * @param dbName
     * @return
     */
    public MongoDatabase getDb(String dbName);

    /**
     * 描述：获取指定mongodb数据库的collection集合
     * @param dbName    数据库名
     * @param collectionName    数据库集合名
     * @return
     */
    public MongoCollection<Document> getCollection(String dbName, String collectionName);
    /**
     * 描述：向指定的数据库中添加一条数据
     * @param collection
     * @param map
     * @return
     */
    public boolean addOne(MongoCollection<Document> collection, Map<String,Object> map);
    /**
     * 描述：向指定的数据库中添加一条数据
     * @param collection
     * @param list
     * @return
     */
    public boolean addMany(MongoCollection<Document> collection, List<Map<String,Object>> list);
    /**
     * 描述：删除数据库dbName中，指定keys和相应values的值
     * @param collection
     * @param filter
     * @return
     */
    public int delete(MongoCollection<Document> collection,Map<String,Object> filter);
    /**
     * 通过ID删除
     * @param coll
     * @param id
     * @return
     */
    public int deleteById(MongoCollection<Document> coll, String id);
    public Document findFirst(MongoCollection<Document> coll, Bson filter, LinkedHashMap<String,SortEnum> sort);
    public long count(MongoCollection<Document> coll, Bson filter);
    public MongoCursor<Document> findTop(MongoCollection<Document> coll, Bson filter,LinkedHashMap<String,SortEnum> sort,int num);
    /**
     * 描述:  条件查询
     * @param coll
     * @param filter
     * @return
     **/
    public MongoCursor<Document> find(MongoCollection<Document> coll, Bson filter,LinkedHashMap<String,SortEnum> sort);
    /**
     * 描述: 分页查询
     * @param coll
     * @param filter
     * @param pageIndex
     * @param pageSize
     * @return
     */
    public MongoCursor<Document> findByPage(MongoCollection<Document> coll, Bson filter, int pageIndex, int pageSize,Map<String,SortEnum> sort);
    /**
     * 描述: 查找对象 - 根据主键_id
     *
     * @param coll
     * @param id
     * @return
     */
    public Document findById(MongoCollection<Document> coll, String id);
    /**
     * 描述：更新数据库dbName，用指定的newValue更新oldValue
     * @param newData
     * @param filter
     * @param coll
     * @return
     */
    public void update(MongoCollection<Document> coll,Map<String,Object> filter,Map<String,Object> newData);
    /**
     * 描述: 更新文档
     *
     * @param coll
     * @param id
     * @param newData
     * @return
     */
    public void updateById(MongoCollection<Document> coll, String id, Map<String,Object> newData);

    /**
     * 描述: 删除集合
     * @param dbName
     * @param collName
     */
    public void dropCollection(String dbName, String collName);
    /**
     * 描述：判断给定的keys和相应的values在指定的dbName的collectionName集合中是否存在
     * @param collection
     * @param filter
     * @return
     */
    boolean isExit(MongoCollection<Document> collection,Bson filter);
    <TResult> List<TResult>  distinct(MongoCollection<Document> collection, Map<String,Object> filter, String field, Class<TResult> clazz);
    MongoCursor<Document> statistics(MongoCollection<Document> coll, Map<String,Object> filter,Document groupField,String statisticType);


}
