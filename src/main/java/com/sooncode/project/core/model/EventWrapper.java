package com.sooncode.project.core.model;

import com.alibaba.fastjson.JSONObject;
import com.sooncode.project.core.annotations.Description;
import com.sooncode.project.core.monitor.Monitor;

import java.util.Date;
import java.util.Map;

/**
 * 事件包装器, 用做事件保存时使用
 */
public class EventWrapper {
    private String id;
    private Class eventType;
    private DomainEvent event;
    private String eventStreamId;
    private int eventVersion;
    private Date createDate;
    private Creater creater;
    private DescriptionModel description;
    public String getId() {
        return id;
    }

    private void setId(String id) {
        this.id = id;
    }

    public DomainEvent getEvent() {
        return event;
    }

    private void setEvent(DomainEvent event) {
        this.event = event;
    }

    public String getEventStreamId() {
        return eventStreamId;
    }

    private void setEventStreamId(String eventStreamId) {
        this.eventStreamId = eventStreamId;
    }

    public int getEventVersion() {
        return eventVersion;
    }

    private void setEventVersion(int eventVersion) {
        this.eventVersion = eventVersion;
    }

    public Date getCreateDate() {
        return createDate;
    }

    public void setCreateDate(Date createDate) {
        this.createDate = createDate;
    }

    public Creater getCreater() {
        return creater;
    }

    private void setCreater(Creater creater) {
        this.creater = creater;
    }

    public Class getEventType() {
        return eventType;
    }

    private void setEventType(Class eventType) {
        this.eventType = eventType;
    }

    public DescriptionModel getDescription() {
        return description;
    }

    public void setDescription(DescriptionModel description) {
        this.description = description;
    }
    public EventWrapper(DomainEvent event, int eventVersion,Date createDate, String streamId, Map<String,Object> creater, Map<String,Object> description){
        setEvent(event);
        setEventVersion(eventVersion);
        setEventStreamId(streamId);
        setId(streamId+"-"+eventVersion);
        setCreateDate(createDate);
        setEventType(event.getClass());
        try {
            JSONObject jsonObject=new JSONObject();
            jsonObject.putAll(creater);
            Creater createrObj = (Creater) jsonObject.toJavaObject(Class.forName(Creater.class.getName()));
            setCreater(createrObj);
            DescriptionModel desc= new DescriptionModel(){{
                setEventDescription(description.get("eventDescription").toString());
                setSourceModelDescription(description.get("sourceModelDescription").toString());
            }};
            setDescription(desc);
        }catch (Exception ex){};
    }
    /**
     * 构造器
     * @param event 事件对象
     * @param eventVersion 事件版本
     * @param streamId 事件id
     */
    public EventWrapper(DomainEvent event, int eventVersion, String streamId,Class<?> sourceClasa){
        setEvent(event);
        setEventVersion(eventVersion);
        setEventStreamId(streamId);
        setId(streamId+"-"+eventVersion);
        setCreateDate(new Date());
        setEventType(event.getClass());
        DescriptionModel helper=new DescriptionModel();
        if(event.getClass().isAnnotationPresent(Description.class))
            helper.setEventDescription(event.getClass().getAnnotation(Description.class).value());
        if(sourceClasa.isAnnotationPresent(Description.class))
            helper.setSourceModelDescription(sourceClasa.getAnnotation(Description.class).value());
        setDescription(helper);
        if(Monitor.New().getCreaterGetter()==null){
            setCreater(null);
        }
        else{
            try {
                ICreaterGetter getter=Monitor.New().getCreaterGetter();
                setCreater(getter.getCurrUser());
            }catch (Exception ex){
                setCreater(null);
            }

        }
    }
    public class DescriptionModel {
        public String eventDescription;
        public String sourceModelDescription;

        public String getEventDescription() {
            return eventDescription;
        }

        public void setEventDescription(String eventDescription) {
            this.eventDescription = eventDescription;
        }

        public String getSourceModelDescription() {
            return sourceModelDescription;
        }

        public void setSourceModelDescription(String sourceModelDescription) {
            this.sourceModelDescription = sourceModelDescription;
        }
    }
}
