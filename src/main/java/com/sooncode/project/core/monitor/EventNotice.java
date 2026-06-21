package com.sooncode.project.core.monitor;

import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 类名: EventNotice
 * 说明: TODO
 * 创建日期: 2021-09-30 15:17
 * 创建人: 赵金歌
 **/
public class EventNotice {
    private Map<String, List<Trigger>> events;
    EventNotice(){
        events=new HashMap<>();
    }
    public Trigger Listen(Class cla){
        return Listen(cla.getName());
    }
    public Trigger Listen(String className){
        List<Trigger> triggers;
        if(this.events.containsKey(className))
            triggers = this.events.get(className);
        else triggers=new ArrayList<>();
        Trigger trigger=new Trigger();
        triggers.add(trigger);
        this.events.put(className,triggers);
        return trigger;
    }
    public void Notice(DomainEvent event, DomainModel entity){
        List<Trigger> triggers=events.get(event.getClass().getName());
        if(triggers==null||triggers.size()==0)return;
        for(Trigger trigger:triggers) {
            if (trigger == null || trigger.triggerFunc == null) return;
            trigger.triggerFunc.run(entity, event);
        }
    }
    public class Trigger {
        IEventFunction triggerFunc;
        public void trigger(IEventFunction func) {
            this.triggerFunc=func;
        }
    }
}
