package com.sooncode.project.core.monitor;

import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.model.*;

public class Monitor {
    private EntityNotice entityNotice;
    private EventNotice eventNotice;
    private StoreNotice storeNotice;
    private ReportRegister reportRegister;
    private IDomainRepository domainRepository;

    private ICreaterGetter createrGetter;
    public static Monitor instance=null;
    private Monitor(){
        entityNotice=new EntityNotice();
        eventNotice=new EventNotice();
    }
    public static Monitor New(){
        instance= Monitor.Singleton.INSTANCE.getInstance();
        return instance;
    }

    public ICreaterGetter getCreaterGetter(){
        return createrGetter;
    }
    private enum Singleton {
        INSTANCE;
        private Monitor instance;
        Singleton() {
            instance = new Monitor();
        }
        public Monitor getInstance() {
            return instance;
        }
    }
    public void Notice(DomainModel entity, FuncType funcType){
        entityNotice.Notice(entity,funcType);
    }
    public void Notice(DomainModel entity, DomainModel oldEvent, FuncType funcType){
        entityNotice.Notice(entity,oldEvent,funcType);
    }
    public void Store(DomainModel entity, EventBoot annotation){
        if(domainRepository!=null&&storeNotice!=null)
            storeNotice.Notice(entity,annotation);
    }
    public void Notice(com.sooncode.project.core.model.DomainEvent event, DomainModel entity){
        eventNotice.Notice(event,entity);
    }
    public EventNotice.Trigger ListenEvent(Class<? extends com.sooncode.project.core.model.DomainEvent> cla){
        return eventNotice.Listen(cla);
    }
    public EntityNotice.Trigger ListenEntity(Class<? extends DomainModel> cla){
        return entityNotice.Listen(cla);
    }
    @Deprecated
    public void ListenCreater(ICreaterGetter listen){
        createrGetter=listen;
    }
    public void ConfigCreater(ICreaterGetter listen){createrGetter=listen;}
    public void ConfigDomainRepository(IDomainRepository repository){
        domainRepository=repository;
        storeNotice=new StoreNotice(domainRepository);
    }
    public void ConfigDBConnection(IDBConnection dbConnection) {
        IEventSourcingRepository eventRepository= dbConnection.getRepository();
        IEventStore eventStore=new EventStore(eventRepository);
        this.domainRepository=new DomainRepository(eventStore);
        this.storeNotice=new StoreNotice(domainRepository);
    }
    public ReportRegister RegisterReport(Class cla,IDomainReportRepository repository){
        if(reportRegister==null)
            reportRegister=new ReportRegister();
        return reportRegister.add(cla,repository);
    }
    public void RegisterLookupModel(String packageName){
        new LookupHandler(packageName,domainRepository);
    }
    public IDomainRepository getDomainRepository(){
        return domainRepository;
    }
}
