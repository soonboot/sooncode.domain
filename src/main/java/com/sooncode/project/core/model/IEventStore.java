package com.sooncode.project.core.model;

import com.sooncode.project.core.finder.Page;

import java.util.List;
import java.util.Map;

/**
 * 事件存储器接口
 */
public interface IEventStore {
    void createNewStream(String streamName, List<DomainEvent> domainEvents, Class<?> cla);
    void appendEventToStream(String streamName,List<DomainEvent> domainEvents,Integer expectedVersion,Class<?> cla);
    void appendEventToStream(String streamName,List<DomainEvent> domainEvents,Class<?> cla);
    void invalid(String streamName,List<DomainEvent> domainEvents,Integer expectedVersion,Class<?> cla);
    List<DomainEvent> getStream(String streamName,int fromVersion,int toVersion);
    Page<EventWrapper> getStream(String modelType, String eventType, String creater, int pageSize, int pageIndex);
    void saveSnapshot(String id , Entity snapshot);
    void deleteSnapshot(String streamId,Class<?> cla);
    <T> T getLatestSnapshot(String id,Class<T> c);
    <T> List<T> getSnapshotList(String streamType,Class<T> c);
}
