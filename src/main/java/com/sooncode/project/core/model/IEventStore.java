package com.sooncode.project.core.model;

import com.sooncode.project.core.finder.Page;

import java.util.List;

/**
 * 事件存储器接口
 */
public interface IEventStore {
    void createNewStream(String streamName, List<DomainEvent> domainEvents, Class<?> cla);
    void appendEventToStream(String streamName,List<DomainEvent> domainEvents,Integer expectedVersion,Class<?> cla);
    void appendEventToStream(String streamName,List<DomainEvent> domainEvents,Class<?> cla);
    void invalid(String streamName,List<DomainEvent> domainEvents,Integer expectedVersion,Class<?> cla);
    void reactivate(String streamName);
    List<DomainEvent> getStream(String streamName,int fromVersion,int toVersion);
    Page<EventWrapper> getStream(String modelType, String eventType, String creater, int pageSize, int pageIndex);
    void saveSnapshot(String id , Entity snapshot);
    void deleteSnapshot(String streamId,Class<?> cla);
    <T> T getLatestSnapshot(String id,Class<T> c);
    <T> List<T> getSnapshotList(String streamType,Class<T> c);
    /** 读取事件流元数据（版本号/失效标志以此为准，快照不持久化 version，不能以快照 version 为准）。无元数据返回 null。 */
    default com.sooncode.project.core.model.EventStream loadMetadata(String streamName) { return null; }
}
