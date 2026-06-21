package com.sooncode.project.core.monitor;

import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.model.Entity;

public class EventFunctionModel<T extends DomainEvent,E extends Entity> {
    private DomainEvent event;
    private E entity;

    public DomainEvent getEvent() {
        return event;
    }

    public void setEvent(DomainEvent event) {
        this.event = event;
    }

    public E getEntity() {
        return entity;
    }

    public void setEntity(E entity) {
        this.entity = entity;
    }
}
