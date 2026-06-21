package com.sooncode.project.core.monitor;

import com.sooncode.project.core.model.Entity;

public class EntityFunctionModel<T extends Entity> {
    private T sourceEntity;
    private T targetEntity;

    public T getSourceEntity() {
        return sourceEntity;
    }

    void setSourceEntity(T sourceEntity) {
        this.sourceEntity = sourceEntity;
    }

    public T getTargetEntity() {
        return targetEntity;
    }

    void setTargetEntity(T targetEntity) {
        this.targetEntity = targetEntity;
    }
}
