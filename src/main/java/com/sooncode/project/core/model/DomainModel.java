package com.sooncode.project.core.model;
import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.finder.Finder;
import com.sooncode.project.core.generic.BasicAddEvent;
import com.sooncode.project.core.generic.BasicDeleteEvent;
import com.sooncode.project.core.generic.BasicModifyEvent;
import com.sooncode.project.core.generic.ReplayEvent;
import com.sooncode.project.core.monitor.Monitor;
import com.sooncode.project.core.utils.EntityConvert;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * 聚合基类, 也是事件溯源模式的聚合基类
 * @param <T>
 */

public abstract class DomainModel<T> extends Entity {
    protected List<DomainEvent> events;
    boolean stored=true;
    private int version;
    public int startVersion =0;

    /**
     * 构造器
     */
    public DomainModel(){
        super();
        events =new ArrayList<>();
    }
    public void replay(List<DomainEvent> events, int fromVersion, int toVersion){
        if(events!=null&&events.size()>0){
            for (DomainEvent event:events){
                apply(event);
            }
        }
        causes(new ReplayEvent(getId(),this,fromVersion,toVersion));
    }

    protected void when(ReplayEvent event){
        EntityConvert.copyPropertys(event.getData(),this);
    }
    /**
     * 应用事件, 会调用聚合对象的when方法, 通过传入不同的事件对象,调用不同的方法
     * @param event
     */
    private void apply(DomainEvent event)
    {

        Class clz=getClass();
        Method method=null;
        while (clz!=null) {
            try {
                method = clz.getDeclaredMethod("when", event.getClass());
                break;
            }catch (NoSuchMethodException ex){}
            clz=clz.getSuperclass();
        }
        if(method==null) {
            event.projectiveEntity(this);
            return;
        }
        method.setAccessible(true);
        try{
            method.invoke(this,event);
            addVersion();
        }
        catch (DomainException ex){
            throw ex;
        }
        catch (IllegalAccessException ex){
            throw new DomainException("执行相关对象的when方法时错误: 访问不被允许");
        }
        catch (InvocationTargetException ex){
            throw new DomainException("执行相关对象的when方法时错误:"+ex.getTargetException().getMessage());
        }
    }

    /**
     * 增加事件的版本号
     * @return
     */
    protected Integer addVersion(){
        version++;
        return version;
    }

    public void add(){
        causes(new BasicAddEvent(),this);
    }
    protected void update(){
        causes(new BasicModifyEvent(),this);
    }
    protected void delete(){
        causes(new BasicDeleteEvent(),this);
    }
    protected void replay(int toVersion){
        causes(new ReplayEvent(getId(),this,0,toVersion));
    }
    protected void replay(int fromVersion, int toVersion){
        causes(new ReplayEvent(getId(),this,fromVersion,toVersion));
    }
    protected void replay(){
        causes(new ReplayEvent(getId(),this,0,this.getVersion()-1));
    }

    /**
     * 事件起因, 注册事件到事件列表中, 同时应用事件.
     * @param event
     */
    protected void causes(DomainEvent event){
        if(event.getId()==null||event.getId().equals(""))
            event.setId(this.getId());
        events.add(event);
        apply(event);
        stored=false;
        if(event.getClass().isAnnotationPresent(EventBoot.class)&&Monitor.instance!=null)
            Monitor.instance.Store(this,event.getClass().getAnnotation(EventBoot.class));
        if(Monitor.instance!=null){
            Monitor.instance.Notice(event,this);
        }
    }
    protected void causes(DomainEvent event, Map params){
        event.convertParam(params);
        causes(event);
    }
    protected void causes(DomainEvent event, Entity objParam){
        event.convertParam(objParam);
        causes(event);
    }
    protected void causes(Class<? extends DomainEvent> cla){
        DomainEvent event=getEvent(cla);
        causes(event);
    }
    protected void causes(Class<? extends DomainEvent> cla, Entity objParam){
        DomainEvent event=getEvent(cla);
        event.convertParam(objParam);
        causes(event);
    }
    protected void causes(Class<? extends DomainEvent> cla, Map params){
        DomainEvent event=getEvent(cla);
        event.convertParam(params);
        causes(event);
    }
    public  Map<String, Object> toMap(){
        return EntityConvert.entityToMap(this);
    }
    public void toEntity(Object targetObj) {
        EntityConvert.EntityToEntity(this,targetObj);
    }
    private DomainEvent getEvent(Class<? extends DomainEvent> cla){
        DomainEvent event=null;
        try {
            event=cla.newInstance();
        }catch(Exception ex){
            ex.printStackTrace();
        }
        if(event==null)
            throw new DomainException("生成事件异常,不能为空的事件");
        return event;
    }
    /*************属性设置**************/
    public int getVersion() {
        return version;
    }
    void setVersion(int version) {
        this.version = version;
    }
}
