package com.sooncode.project.core.model;

import com.sooncode.project.core.annotations.ModelSnapshot;
import com.sooncode.project.core.finder.Page;

import java.util.*;

/**
 * 事件存储器实现类, 实现对事件的保存动作.
 */
public class EventStore implements IEventStore {
    private static IEventSourcingRepository _repository;
    public  EventStore(IEventSourcingRepository repository){
        _repository=repository;
    }

    @Override
    public void createNewStream(String streamName, List<DomainEvent> domainEvents, Class<?> cla) {
        EventStream eventStream=new EventStream(streamName,cla);
        eventStream.setCreateDate(new Date());
        _repository.addMetadata(eventStream);
        this.appendEventToStream(streamName,domainEvents,cla);
    }

    @Override
    public void appendEventToStream(String streamName, List<DomainEvent> domainEvents, Integer expectedVersion,Class<?> cla) {
        if(domainEvents.size()==0) return;
        EventStream eventStream=_repository.loadMetadata(streamName);
        if(eventStream==null)
            throw new DomainException("没有找到元数据:"+streamName);
        if(eventStream.getIsInvalid()==1)
            throw new DomainException("数据已经失效:"+streamName);
        if(expectedVersion!=null){
            checkForConcurrencyError(expectedVersion,eventStream);
        }
        for(DomainEvent event:domainEvents){
            _repository.saveStream(eventStream.registerEvent(event,cla));
        }
        _repository.updateMetadata(eventStream);
    }
    @Override
    public void appendEventToStream(String streamName, List<DomainEvent> domainEvents,Class<?> cla) {
        this.appendEventToStream(streamName,domainEvents,null,cla);
    }

    @Override
    public void invalid(String streamName,List<DomainEvent> domainEvents,Integer expectedVersion,Class<?> cla) {
        this.appendEventToStream(streamName,domainEvents,expectedVersion,cla);
        EventStream eventStream=_repository.loadMetadata(streamName);
        eventStream.Invalid();
        _repository.updateMetadata(eventStream);
    }

    @Override
    public List<DomainEvent> getStream(String streamName, int fromVersion, int toVersion) {
        List<EventWrapper> eventWrappers=_repository.getStream(streamName,fromVersion,toVersion);
        if(eventWrappers.size()==0) return null;
        List<DomainEvent> events=new ArrayList<>();
        for(EventWrapper event:eventWrappers){
            events.add(event.getEvent());
        }
        return events;
    }

    @Override
    public Page<EventWrapper> getStream(String modelType, String eventType, String creater, int pageSize, int pageIndex) {
        return _repository.getStream(modelType,eventType,creater,pageSize,pageIndex);
    }

    @Override
    public void saveSnapshot(String id, Entity snapshot) {
        SnapshotWrapper eventWrapper=new SnapshotWrapper(id,snapshot);
        _repository.saveSnapshotWrapper(eventWrapper,getCollectionName(snapshot.getClass()));
    }

    @Override
    public void deleteSnapshot(String streamId, Class<?> cla) {
        _repository.deleteSnapshotWrapper(streamId,getCollectionName(cla));
    }

    @Override
    public <T> T getLatestSnapshot(String id, Class<T> c) {
        SnapshotWrapper latestSnapshot=_repository.getSnapshotWrapper(id, getCollectionName(c));
        if(latestSnapshot==null){
            return  null;
        }
        else{
            return (T)latestSnapshot.getSnapshot();
        }
    }

    @Override
    public <T> List<T> getSnapshotList(String streamType, Class<T> cla) {
        List<SnapshotWrapper> wrapperList= _repository.getSnapshotWrapperList(streamType,getCollectionName(cla));
        List<T> result=new ArrayList<>();
        for(SnapshotWrapper wrapper:wrapperList){
            result.add((T)wrapper.getSnapshot());
        }
        return result;
    }

    private static void checkForConcurrencyError(Integer expectedVersion,EventStream stream){
        Integer lastUpdatedVersion = stream.getVersion();
        if(lastUpdatedVersion != expectedVersion){
            String error=String.format("预期版本号: %d。 找到的版本号: %d",expectedVersion,lastUpdatedVersion);
            throw new CheckForConcurrencyException(error);
        }
    }
    private String getCollectionName(Class<?> cType) {
        String collectionName="";
        if(cType.isAnnotationPresent(ModelSnapshot.class)){
            ModelSnapshot modelSnapshot = cType.getAnnotation(ModelSnapshot.class);
            collectionName =modelSnapshot.value();
            if(collectionName==null|| collectionName.isEmpty()){
                collectionName=modelSnapshot.collectionName();
            }
        }
        return collectionName;
    }
}
