package com.sooncode.project.core.model;

import com.sooncode.project.core.monitor.EntityNotice;
import com.sooncode.project.core.monitor.EventNotice;
import com.sooncode.project.core.monitor.FuncType;
import com.sooncode.project.core.monitor.Monitor;

/**
 * 类名: EventManager
 * 说明: TODO
 * 创建日期: 2021-09-30 9:58
 * 创建人: 赵金歌
 **/
@Deprecated
public class EventMonitor {
    static EventMonitor instance=null;
    private EventMonitor(){
        Monitor.New();
    }
    public static EventMonitor New(){
        instance= Singleton.INSTANCE.getInstance();
        return instance;
    }

    ICreaterGetter getCreaterGetter(){
        return Monitor.instance.getCreaterGetter();
    }
    private enum Singleton {
        INSTANCE;
        private EventMonitor instance;
        Singleton() {
            instance = new EventMonitor();
        }
        public EventMonitor getInstance() {
            return instance;
        }
    }
    void Notice(DomainModel entity, FuncType funcType){
        Monitor.instance.Notice(entity,funcType);
    }
    void Notice(DomainModel entity, DomainModel oldEvent, FuncType funcType){
        Monitor.instance.Notice(entity,funcType);
    }
    void Notice(DomainEvent event, DomainModel entity){
        Monitor.instance.Notice(event,entity);
    }
    public EventNotice.Trigger ListenEvent(Class<? extends DomainEvent> cla){
        return Monitor.instance.ListenEvent(cla);
    }
    public EntityNotice.Trigger ListenEntity(Class<? extends DomainModel> cla){
        return Monitor.instance.ListenEntity(cla);
    }
    public void ListenCreater(ICreaterGetter listen){
        Monitor.instance.ConfigCreater(listen);
    }
}
