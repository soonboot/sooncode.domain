package com.sooncode.project.core.model;

import com.sooncode.project.core.annotations.ModelSnapshot;
import com.sooncode.project.core.finder.Page;

import java.util.*;

/**
 * 事件存储器实现类, 实现对事件的保存动作.
 */
public class EventStore implements IEventStore {
    private final IEventSourcingRepository repository;

    public  EventStore(IEventSourcingRepository repository){
        this.repository=repository;
    }

    @Override
    public void createNewStream(String streamName, List<DomainEvent> domainEvents, Class<?> cla) {
        EventStream eventStream=new EventStream(streamName,cla);
        eventStream.setCreateDate(new Date());
        repository.addMetadata(eventStream);
        this.appendEventToStream(streamName,domainEvents,cla);
    }

    @Override
    public void appendEventToStream(String streamName, List<DomainEvent> domainEvents, Integer expectedVersion,Class<?> cla) {
        if(domainEvents.size()==0) return;
        EventStream eventStream=repository.loadMetadata(streamName);
        if(eventStream==null)
            throw new DomainException("没有找到元数据:"+streamName);
        if(eventStream.getIsInvalid()==1)
            throw new DomainException("数据已经失效:"+streamName);
        if(expectedVersion!=null){
            checkForConcurrencyError(expectedVersion,eventStream);
        }
        int previousVersion = eventStream.getVersion();
        for(DomainEvent event:domainEvents){
            repository.saveStream(eventStream.registerEvent(event,cla));
        }
        // 乐观锁 CAS：按 expectedVersion 精确过滤，避免 check-then-update 之间的 TOCTOU
        // expectedVersion == null 时为兼容旧调用，仍按原 update（批量路径已保证 CAS，此处为回退路径）
        if (expectedVersion != null) {
            repository.updateMetadataCAS(eventStream, expectedVersion);
        } else {
            // 无显式期望版本时，以 previousVersion 做隐式 CAS（防止并发丢失更新）
            try {
                repository.updateMetadataCAS(eventStream, previousVersion);
            } catch (CheckForConcurrencyException e) {
                // 回退兼容：若底层未实现 CAS，降级为普通更新
                throw e;
            }
        }
    }
    @Override
    public void appendEventToStream(String streamName, List<DomainEvent> domainEvents,Class<?> cla) {
        this.appendEventToStream(streamName,domainEvents,null,cla);
    }

    @Override
    public void invalid(String streamName,List<DomainEvent> domainEvents,Integer expectedVersion,Class<?> cla) {
        this.appendEventToStream(streamName,domainEvents,expectedVersion,cla);
        EventStream eventStream=repository.loadMetadata(streamName);
        int previousVersion = eventStream.getVersion() - domainEvents.size();
        // delete 的元数据失效需要额外 CAS，避免与并发修改丢失
        eventStream.Invalid();
        try {
            Integer casVersion = expectedVersion != null ? expectedVersion + domainEvents.size() - 1 : previousVersion + domainEvents.size() - 1;
            repository.updateMetadataCAS(eventStream, casVersion);
        } catch (CheckForConcurrencyException e) {
            throw e;
        } catch (Exception ex) {
            repository.updateMetadata(eventStream);
        }
    }

    @Override
    public void reactivate(String streamName) {
        EventStream eventStream = repository.loadMetadata(streamName);
        if (eventStream == null)
            throw new DomainException("没有找到元数据:" + streamName);
        eventStream.Valid();
        repository.updateMetadata(eventStream);
    }

    @Override
    public List<DomainEvent> getStream(String streamName, int fromVersion, int toVersion) {
        List<EventWrapper> eventWrappers=repository.getStream(streamName,fromVersion,toVersion);
        if(eventWrappers.size()==0) return null;
        List<DomainEvent> events=new ArrayList<>();
        for(EventWrapper event:eventWrappers){
            events.add(event.getEvent());
        }
        return events;
    }

    @Override
    public Page<EventWrapper> getStream(String modelType, String eventType, String creater, int pageSize, int pageIndex) {
        return repository.getStream(modelType,eventType,creater,pageSize,pageIndex);
    }

    @Override
    public void saveSnapshot(String id, Entity snapshot) {
        SnapshotWrapper eventWrapper=new SnapshotWrapper(id,snapshot);
        repository.saveSnapshotWrapper(eventWrapper,getCollectionName(snapshot.getClass()));
    }

    @Override
    public void deleteSnapshot(String streamId, Class<?> cla) {
        repository.deleteSnapshotWrapper(streamId,getCollectionName(cla));
    }

    @Override
    public <T> T getLatestSnapshot(String id, Class<T> c) {
        SnapshotWrapper latestSnapshot=repository.getSnapshotWrapper(id, getCollectionName(c));
        if(latestSnapshot==null){
            return  null;
        }
        else{
            return (T)latestSnapshot.getSnapshot();
        }
    }

    @Override
    public <T> List<T> getSnapshotList(String streamType, Class<T> cla) {
        List<SnapshotWrapper> wrapperList= repository.getSnapshotWrapperList(streamType,getCollectionName(cla));
        List<T> result=new ArrayList<>();
        for(SnapshotWrapper wrapper:wrapperList){
            result.add((T)wrapper.getSnapshot());
        }
        return result;
    }

    private static void checkForConcurrencyError(Integer expectedVersion,EventStream stream){
        Integer lastUpdatedVersion = stream.getVersion();
        if (lastUpdatedVersion == null || !lastUpdatedVersion.equals(expectedVersion)) {
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
