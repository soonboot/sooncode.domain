package com.sooncode.project.core.model;

import java.util.Date;

/**
 * 事件流, 也是事件元数据的类结构
 */
public class EventStream {
    private String id;
    private Integer version;
    private Class entityType;
    private int isInvalid=0;
    private Date createDate;
    public String getId() {
        return id;
    }
    private void setId(String id) {
        this.id = id;
    }


    public Integer getVersion() {
        return version;
    }
    private void setVersion(Integer version) {
        this.version = version;
    }

    public int getIsInvalid() {
        return isInvalid;
    }
    private void setIsInvalid(int isInvalid) {
        this.isInvalid = isInvalid;
    }

    public Class getEntityType() {
        return entityType;
    }

    private void setEntityType(Class entityType) {
        this.entityType = entityType;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public void setCreateDate(Date createDate) {
        this.createDate = createDate;
    }

    /**
     * 空构造器, 为反序列化使用
     */
    private EventStream(){}

    /**
     * 构造器 新建业务实体对象时,需要构建元数据
     * @param id 实体对象ID
     * @param cla 实体类型
     */
    public  EventStream(String id,Class cla){
        setId(id);
        setVersion(0);
        setEntityType(cla);
    }

    /**
     * 构造器
     * @param id 实体对象ID
     * @param version 当前版本
     * @param invalid 元数据是否失败, 其实就是删除
     * @param cla 实体的类型
     */
    public  EventStream(String id,Integer version,int invalid,Class cla,Date createDate){
        setId(id);
        setVersion(version);
        setIsInvalid(invalid);
        setEntityType(cla);
        setCreateDate(createDate);
    }

    /**
     * 失效, 使用元数据失效, 其实就是这个数据被删除了,
     * @return
     */
    public EventStream Invalid(){
        setIsInvalid(1);
        return this;
    }


    /**
     * 注册事件, 注册事件会生成事件包装器, 方便对事件流进行存储
     * @param event 事件对象
     * @return
     */
    public EventWrapper registerEvent(DomainEvent event,Class<?> sourceClass){
        version++;
        return new EventWrapper(event,version,id,sourceClass);
    }


}
