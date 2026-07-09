package com.sooncode.project.core.generic;

import com.alibaba.fastjson.annotation.JSONField;
import com.sooncode.project.core.annotations.Description;
import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.model.Entity;
import com.sooncode.project.core.monitor.FuncType;

/**
 * 事件重放。承载一份聚合根快照（{@code data}），DomainModel.replay 会把 data 拷回聚合根。
 *
 * <p>注意：真正的"从持久层拉事件流再 replay"应该走 {@code replayFromStore} 抽象接口，
 * 而不是直接构造本事件。
 */
@Description("事件重放")
@EventBoot(StoreFunc = FuncType.replay)
public class ReplayEvent extends DomainEvent {
    @JSONField(deserializeUsing = ReplayEventDeserialize.class)
    private Entity data;
    private int fromVersion;
    private int toVersion;

    public ReplayEvent(String aggregateId, Entity data, int fromVersion, int toVersion) {
        super(aggregateId);
        setData(data);
        setFromVersion(fromVersion);
        setToVersion(toVersion);
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
}
