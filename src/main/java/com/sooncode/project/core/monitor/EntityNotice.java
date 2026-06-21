package com.sooncode.project.core.monitor;

import com.sooncode.project.core.model.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 类名: EntityNotice
 * 说明: TODO
 * 创建日期: 2021-09-30 15:18
 * 创建人: 赵金歌
 **/
public class EntityNotice {
    private Map<String,List<Trigger>> entitys;
    EntityNotice(){
        this.entitys=new HashMap<>();
    }
    public Trigger Listen(Class cla){
        return Listen(cla.getName());
    }
    public Trigger Listen(String className){
        List<Trigger> triggers;
        if(this.entitys.containsKey(className))
            triggers = this.entitys.get(className);
        else triggers=new ArrayList<>();
        Trigger trigger=new Trigger();
        triggers.add(trigger);
        this.entitys.put(className,triggers);
        return trigger;
    }
    public <T extends Entity> void Notice(T entity, FuncType funcType){
        Notice(entity,null,funcType);
    }
    <T extends Entity> void Notice(T entity,T oldEntity,FuncType funcType) {
        List<Trigger> triggers=entitys.get(entity.getClass().getName());
        if(triggers==null||triggers.size()==0)return;
        for(Trigger trigger:triggers){
            if(trigger==null||trigger.funcs.get(funcType)==null)continue;
            EntityFunctionModel model=new EntityFunctionModel();
            model.setSourceEntity(oldEntity);
            model.setTargetEntity(entity);
            trigger.funcs.get(funcType).run(model);
        }
    }
    public class Trigger {
        Map<FuncType, IEntityFunction> funcs;
        Trigger(){
            funcs=new HashMap<>();
        }
        public  Trigger add(IEntityFunction func) {
            funcs.put(FuncType.add,func);
            return this;
        }
        public  Trigger modify(IEntityFunction func) {
            funcs.put(FuncType.modify,func);
            return this;
        }
        public  Trigger delete(IEntityFunction func) {
            funcs.put(FuncType.delete,func);
            return this;
        }
        public  Trigger replay(IEntityFunction func) {
            funcs.put(FuncType.replay,func);
            return this;
        }
    }
}
