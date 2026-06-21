package com.sooncode.project.core.model;

import java.util.Date;

/**
 * 事件快照的包装器, 主要是对事件快照数据保存使用
 */
public class SnapshotWrapper {
    private String streamId;
    private Entity snapshot;
    private Class<?> snapshotType;
    private Date createDate;

    public String getStreamId() {
        return streamId;
    }
    private void setStreamId(String streamId) {
        this.streamId = streamId;
    }

    public Entity getSnapshot() {
        return snapshot;
    }
    private void setSnapshot(Entity snapshot) {
        this.snapshot = snapshot;
    }

    public Date getCreateDate() {
        return createDate;
    }
    public void setCreateDate(Date createDate) {
        this.createDate = createDate;
    }

    public Class<?> getSnapshotType() {
        return snapshotType;
    }
    private void setSnapshotType(Class<?> snapshotType) {
        this.snapshotType = snapshotType;
    }

    /**
     * 构造器, 在新建快照包装器时, 要求传入快照的数据实体与快照的ID
     * @param id
     * @param snapshot
     */
    public SnapshotWrapper(String id,Entity snapshot){
        setStreamId(id);
        setSnapshot(snapshot);
        setSnapshotType(snapshot.getClass());
        setCreateDate(new Date());
    }
}
