package com.sooncode.project.core.session;

import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.model.Entity;

import java.util.List;

@FunctionalInterface
public interface ISessionComplete {
    void run(List<Entity> entitys);
}
