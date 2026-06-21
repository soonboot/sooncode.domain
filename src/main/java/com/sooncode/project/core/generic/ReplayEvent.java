package com.sooncode.project.core.generic;
import com.alibaba.fastjson.annotation.JSONField;
import com.sooncode.project.core.annotations.Description;
import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.model.Entity;
import com.sooncode.project.core.monitor.FuncType;

/**
 * 类名: ReplayEvent
 * 说明: TODO
 * 创建日期: 2021-09-30 16:27
 * 创建人: 赵金歌
 **/
@Description("事件重放")
@EventBoot(StoreFunc = FuncType.replay)
public class ReplayEvent extends DomainEvent {
    @JSONField(deserializeUsing = ReplayEventDeserialize.class)
    private Entity data;
    private Class wclass;
    private int fromVersion;
    private int toVersion;
    public ReplayEvent(String aggregateId, Entity data,int fromVersion,int toVersion) {
        super(aggregateId);
        setData(data);
        setFromVersion(fromVersion);
        setToVersion(toVersion);
        setWclass(data.getClass());
    }

    public Entity getData() {
        return data;
    }

    public void setData(Entity data) {
        this.data = data;
    }

    public int getFromVersion() {
        return fromVersion;
    }

    public void setFromVersion(int fromVersion) {
        this.fromVersion = fromVersion;
    }

    public int getToVersion() {
        return toVersion;
    }

    public void setToVersion(int toVersion) {
        this.toVersion = toVersion;
    }

    public Class getWclass() {
        return wclass;
    }

    public void setWclass(Class wclass) {
        this.wclass = wclass;
    }
}
