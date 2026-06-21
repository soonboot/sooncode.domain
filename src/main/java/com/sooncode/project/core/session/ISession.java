package com.sooncode.project.core.session;
import com.sooncode.project.core.model.Entity;

import java.util.List;

public interface ISession {
    void add(Entity entity);
    void commit();
    void rollback();
    List<Entity> getEntitys();
    void onSuccess(ISessionComplete function);
    void setSessionFunction(ISessionFunction function);
}
