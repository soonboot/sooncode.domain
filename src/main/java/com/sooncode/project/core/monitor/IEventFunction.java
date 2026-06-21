package com.sooncode.project.core.monitor;

import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.model.DomainModel;

@FunctionalInterface
public interface IEventFunction {
    void run(DomainModel entity, DomainEvent event);
}
