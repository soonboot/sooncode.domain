package com.sooncode.project.core.recycle;

import java.util.Date;
import java.util.Map;

public class RecycleBinRecord {
    private String id;
    private String streamId;
    private String entityId;
    private String entityType;
    private Map<String, Object> snapshotDoc;
    private Date deleteTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStreamId() { return streamId; }
    public void setStreamId(String streamId) { this.streamId = streamId; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public Map<String, Object> getSnapshotDoc() { return snapshotDoc; }
    public void setSnapshotDoc(Map<String, Object> snapshotDoc) { this.snapshotDoc = snapshotDoc; }
    public Date getDeleteTime() { return deleteTime; }
    public void setDeleteTime(Date deleteTime) { this.deleteTime = deleteTime; }
}
