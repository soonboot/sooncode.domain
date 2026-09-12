package com.sooncode.project.core.monitor;

import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.batcher.BatchOptions;
import com.sooncode.project.core.config.InfraConfig;
import com.sooncode.project.core.batcher.BatchRepository;
import com.sooncode.project.core.batcher.BatchStore;
import com.sooncode.project.core.batcher.IBatchRepository;
import com.sooncode.project.core.batcher.IBatchRepositoryProvider;
import com.sooncode.project.core.model.*;
import com.sooncode.project.core.trash.Trash;

public class Monitor {
    private EntityNotice entityNotice;
    private EventNotice eventNotice;
    private StoreNotice storeNotice;
    private ReportRegister reportRegister;
    private IDomainRepository domainRepository;
    private BatchRepository<?> batchRepository;

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
        batchRepository=new BatchRepository<>(repository);
        storeNotice=new StoreNotice(domainRepository);
    }
    /** 设置全局是否使用事务（需事务时设为 true（要求副本集），默认 false 单机开箱即用）。建议在 Monitor.New() 之后、ConfigDBConnection 之前调用。 */
    public Monitor setAtomic(boolean atomic) {
        InfraConfig.setAtomic(atomic);
        return this;
    }
    /** 别名：是否使用事务 */
    public Monitor setTransactional(boolean transactional) {
        return setAtomic(transactional);
    }
    /** 链式：设置事务开关后返回自身，便于 fluent 调用 */
    public Monitor withAtomic(boolean atomic) {
        return setAtomic(atomic);
    }
    public boolean isAtomic() {
        return InfraConfig.isAtomic();
    }
    public boolean isTransactional() {
        return InfraConfig.isTransactional();
    }
    public void ConfigDBConnection(IDBConnection dbConnection) {
        ConfigDBConnection(dbConnection, (BatchOptions) null);
    }
    public void ConfigDBConnection(IDBConnection dbConnection, boolean atomic) {
        InfraConfig.setAtomic(atomic);
        ConfigDBConnection(dbConnection, (BatchOptions) null);
    }
    public void ConfigDBConnection(IDBConnection dbConnection, BatchOptions options) {
        if (options != null) {
            InfraConfig.setAtomic(options.isAtomic());
        }
        IEventSourcingRepository eventRepository= dbConnection.getRepository();
        IEventStore eventStore=new EventStore(eventRepository);
        Trash trashRepository=new Trash(eventStore);
        this.domainRepository=new DomainRepository(eventStore, trashRepository);
        IBatchRepository batchStoreRepository = dbConnection instanceof IBatchRepositoryProvider
                ? ((IBatchRepositoryProvider) dbConnection).getBatchRepository() : null;
        this.batchRepository=new BatchRepository<>(domainRepository,
                batchStoreRepository == null ? null : new BatchStore(batchStoreRepository),
                trashRepository);
        this.storeNotice=new StoreNotice(domainRepository);
    }
    public ReportRegister RegisterReport(Class cla,IDomainReportRepository repository){
        if(reportRegister==null)
            reportRegister=new ReportRegister();
        return reportRegister.add(cla,repository);
    }
    public LookupHandler RegisterLookupModel(String packageName){
        return new LookupHandler(packageName,domainRepository);
    }

    /**
     * 推荐：显式注入 IEventSourcingRepository，彻底解耦 MongoSingle。
     * 适用于需要单测或非 Mongo 存储的场景。
     */
    public LookupHandler RegisterLookupModel(String packageName, IEventSourcingRepository sourceRepo){
        return new LookupHandler(packageName, domainRepository, sourceRepo);
    }
    public IDomainRepository getDomainRepository(){
        return domainRepository;
    }

    public BatchRepository<?> getBatchRepository() {
        return batchRepository;
    }
}
