package com.sooncode.project.core.session;
import com.sooncode.project.core.model.Entity;


import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class DomainSession implements ISession{
    private ConcurrentLinkedQueue<ISessionFunction> functions=null;
    private ISessionComplete successFunction;
    private  List<Entity> entities=null;
    public DomainSession(){
        functions=new ConcurrentLinkedQueue<>();
        entities=new ArrayList<>();
    }
    @Override
    public void add(Entity entity){
        SessionManager.put(entity,this);
        entities.add(entity);
    }
    @Override
    public void setSessionFunction(ISessionFunction function){
        functions.add(function);
    }
    @Override
    public void commit(){
        while (!functions.isEmpty()){
            ISessionFunction function=functions.poll();
            function.run();
        }
        for (Entity entity:entities){
            SessionManager.remove(entity);
        }
        if(successFunction!=null)
            successFunction.run(entities);
        entities.clear();
    }
    @Override
    public void rollback(){
        for (Entity entity:entities){
            SessionManager.remove(entity);
        }
        entities.clear();
    }

    @Override
    public List<Entity> getEntitys() {
        return entities;
    }

    @Override
    public void onSuccess(ISessionComplete function) {
        this.successFunction=function;
    }

}
