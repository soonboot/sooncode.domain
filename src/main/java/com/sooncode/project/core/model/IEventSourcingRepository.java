package com.sooncode.project.core.model;

import com.sooncode.project.core.finder.Page;

import java.util.List;
import java.util.Map;

/**
 * 事件溯源存储库
 */
public interface IEventSourcingRepository {
    void addMetadata(EventStream stream);
    void updateMetadata(EventStream stream);
    void saveStream(EventWrapper stream);
    EventStream loadMetadata(String streamName);
    List<EventWrapper> getStream(String streamName, Integer fromVersion, Integer toVersion);
    Page<EventWrapper> getStream(String modelType, String eventType, String creater, int pageSize, int pageIndex);
    void saveSnapshotWrapper(SnapshotWrapper eventStream,String modelCollection);
    void deleteSnapshotWrapper(String streamId,String modelCollection);
    SnapshotWrapper getSnapshotWrapper(String streamName,String modelCollection);
    List<SnapshotWrapper> getSnapshotWrapperList(String streamType,String modelCollection);
    Map<String,Object> getSnapshotDoc(String streamType,String modelCollection);
}
