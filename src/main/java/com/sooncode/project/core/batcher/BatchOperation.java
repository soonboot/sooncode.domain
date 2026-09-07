package com.sooncode.project.core.batcher;

import com.sooncode.project.core.monitor.FuncType;
import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.SnapshotWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 领域层生成的批量持久化命令。基础设施层只消费该命令，不参与领域事件生成。
 */
public final class BatchOperation {
    public enum Type { ADD, MODIFY, DELETE }

    private final Type type;
    private final DomainModel entity;
    private final DomainModel oldEntity;
    private final List<DomainEvent> events;
    private final Integer expectedVersion;
    private final boolean skipEventSourcing;
    private final boolean trash;

    public BatchOperation(Type type, DomainModel entity, DomainModel oldEntity,
                          List<DomainEvent> events, Integer expectedVersion,
                          boolean skipEventSourcing) {
        this(type, entity, oldEntity, events, expectedVersion, skipEventSourcing, true);
    }

    public BatchOperation(Type type, DomainModel entity, DomainModel oldEntity,
                          List<DomainEvent> events, Integer expectedVersion,
                          boolean skipEventSourcing, boolean trash) {
        this.type = type;
        this.entity = entity;
        this.oldEntity = oldEntity;
        this.events = events == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(events));
        this.expectedVersion = expectedVersion;
        this.skipEventSourcing = skipEventSourcing;
        this.trash = trash;
    }

    public Type getType() {
        return type;
    }

    public DomainModel getEntity() {
        return entity;
    }

    public DomainModel getOldEntity() {
        return oldEntity;
    }

    public List<DomainEvent> getEvents() {
        return events;
    }

    public Integer getExpectedVersion() {
        return expectedVersion;
    }

    public boolean isSkipEventSourcing() {
        return skipEventSourcing;
    }

    public boolean isTrash() {
        return trash;
    }

    /** 将当前实体转换为统一的快照模型，供具体存储实现进行持久化映射。 */
    public SnapshotWrapper snapshot() {
        return new SnapshotWrapper(streamName(), entity);
    }

    public FuncType getFuncType() {
        switch (type) {
            case ADD: return FuncType.add;
            case MODIFY: return FuncType.modify;
            case DELETE: return FuncType.delete;
            default: throw new DomainException("未知批量操作类型:" + type);
        }
    }

    public String streamName() {
        return String.format("%s-%s", entity.getClass().getName(), entity.getId());
    }

    public String snapshotCollection() {
        com.sooncode.project.core.annotations.ModelSnapshot annotation =
                entity.getClass().getAnnotation(com.sooncode.project.core.annotations.ModelSnapshot.class);
        if (annotation != null) {
            if (annotation.value() != null && !annotation.value().isEmpty()) return annotation.value();
            if (annotation.collectionName() != null && !annotation.collectionName().isEmpty()) return annotation.collectionName();
        }
        return "eventSnapshot";
    }
}
