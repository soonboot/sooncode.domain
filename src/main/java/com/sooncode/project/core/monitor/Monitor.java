package com.sooncode.project.core.monitor;

import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.batcher.BatchOptions;
import com.sooncode.project.core.config.InfraConfig;
import com.sooncode.project.core.batcher.BatchRepository;
import com.sooncode.project.core.batcher.BatchStore;
import com.sooncode.project.core.batcher.IBatchRepository;
import com.sooncode.project.core.batcher.IBatchRepositoryProvider;
import com.sooncode.project.core.lookup.LookupHandler;
import com.sooncode.project.core.model.*;
import com.sooncode.project.core.spi.IInfraFactoryProvider;
import com.sooncode.project.core.spi.InfraContext;
import com.sooncode.project.core.spi.InfraFactory;
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
    // ===== Lookup 调优配置：单一数据源为 InfraConfig，Monitor 仅做委托（P1-1 打破 Monitor↔LookupHandler 循环）=====
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
    /** Lookup 分页大小，默认 500，委托 InfraConfig（构造后动态生效，需配合 Handler.refreshConfig() 或重建 Handler） */
    public Monitor setLookupPageSize(int pageSize) {
        InfraConfig.setLookupPageSize(pageSize);
        return this;
    }
    public int getLookupPageSize() { return InfraConfig.getLookupPageSize(); }
    /** Lookup 异步阈值，小于该扇出同步执行，默认 10 */
    public Monitor setLookupAsyncThreshold(int threshold) {
        InfraConfig.setLookupAsyncThreshold(threshold);
        return this;
    }
    public int getLookupAsyncThreshold() { return InfraConfig.getLookupAsyncThreshold(); }
    /** Lookup 高频去重窗口（ms），默认 500 */
    public Monitor setLookupCoalesceWindowMs(long ms) {
        InfraConfig.setLookupCoalesceWindowMs(ms);
        return this;
    }
    public long getLookupCoalesceWindowMs() { return InfraConfig.getLookupCoalesceWindowMs(); }
    /** Lookup 批量写每批大小，默认 500（与分页对齐 1:1），委托 InfraConfig 后即时生效 */
    public Monitor setLookupBulkBatchSize(int batchSize) {
        InfraConfig.setLookupBulkBatchSize(batchSize);
        return this;
    }
    public int getLookupBulkBatchSize() { return InfraConfig.getLookupBulkBatchSize(); }
    /** 链式批量配置 Lookup */
    public Monitor withLookupConfig(int pageSize, int asyncThreshold, long coalesceMs, int bulkBatchSize) {
        setLookupPageSize(pageSize);
        setLookupAsyncThreshold(asyncThreshold);
        setLookupCoalesceWindowMs(coalesceMs);
        setLookupBulkBatchSize(bulkBatchSize);
        return this;
    }

    public void ConfigDBConnection(IDBConnection dbConnection) {
        ConfigDBConnection(dbConnection, (BatchOptions) null);
    }
    public void ConfigDBConnection(IDBConnection dbConnection, boolean atomic) {
        InfraConfig.setAtomic(atomic);
        ConfigDBConnection(dbConnection, (BatchOptions) null);
    }
    /**
     * 新链路（推荐，多数据库）：显式传入基础设施上下文，成套仓储由 {@link InfraFactory} 创建。
     * 例：{@code monitor.ConfigDBConnection(InfraContext.of(new MongoFactory(dao, db)).publish());}
     * PG 只需新增 {@code PgFactory}，本方法零改动。
     */
    public void ConfigDBConnection(InfraContext context) {
        ConfigDBConnection(context, (BatchOptions) null);
    }

    /**
     * 新链路（推荐，多数据库）：显式传入基础设施上下文 + 批量选项。
     */
    public void ConfigDBConnection(InfraContext context, BatchOptions options) {
        if (context == null) {
            throw new IllegalArgumentException("InfraContext 不能为空");
        }
        if (options != null) {
            InfraConfig.setAtomic(options.isAtomic());
        }
        InfraFactory factory = context.factory();
        try {
            factory.initSchema();
        } catch (Exception e) {
            throw new DomainException("基础设施自举失败 factory=" + factory.name() + ": " + e.getMessage(), e);
        }
        // 成套仓储由同一工厂产出；工厂异常向上传播并附带 factory 名，禁止吞掉后静默混库。
        // 先组装局部变量、成功后再赋值字段，半初始化不污染已有状态。
        final IEventSourcingRepository eventRepository;
        final Trash trashRepository;
        final IBatchRepository batchStoreRepository;
        final IDomainRepository domainRepo;
        final BatchRepository<?> batchRepo;
        final StoreNotice notice;
        try {
            eventRepository = factory.events();
            if (eventRepository == null) {
                throw new DomainException("基础设施装配失败：factory=" + factory.name() + " 返回的 IEventSourcingRepository 为 null");
            }
            IEventStore eventStore=new EventStore(eventRepository);
            trashRepository=new Trash(eventStore, factory.trash());
            domainRepo=new DomainRepository(eventStore, trashRepository);
            batchStoreRepository = factory.batch();
            batchRepo=new BatchRepository<>(domainRepo,
                    batchStoreRepository == null ? null : new BatchStore(batchStoreRepository),
                    trashRepository);
            notice=new StoreNotice(domainRepo);
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DomainException("基础设施装配失败 factory=" + factory.name() + ": " + e.getMessage(), e);
        } catch (Exception e) {
            throw new DomainException("基础设施装配失败 factory=" + factory.name() + ": " + e.getMessage(), e);
        }
        this.domainRepository=domainRepo;
        this.batchRepository=batchRepo;
        this.storeNotice=notice;
    }

    /**
     * 新链路（推荐，多数据库）：直接传入工厂，内部转为上下文。
     */
    public void ConfigDBConnection(InfraFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("InfraFactory 不能为空");
        }
        // 成功后发布：内层 ConfigDBConnection(InfraContext, options) 不依赖静态 current，
        // 先执行、成功再 publish，失败不留脏 current（P0-3）。
        InfraContext context = InfraContext.of(factory);
        ConfigDBConnection(context, (BatchOptions) null);
        context.publish();
    }

    public void ConfigDBConnection(IDBConnection dbConnection, BatchOptions options) {
        // 新链路兼容：连接若自带整套工厂（如 MongoConnection）先取出备用而不发布；
        // Trash 显式注入 factory.trash()，不依赖静态 current；全部装配成功后再 publish，
        // 失败不留脏 current（P0-3 同类修复，老链路行为不变）。
        InfraFactory factory = null;
        if (dbConnection instanceof IInfraFactoryProvider) {
            try {
                factory = ((IInfraFactoryProvider) dbConnection).getInfraFactory();
            } catch (Exception ignored) {
            }
        }
        if (options != null) {
            InfraConfig.setAtomic(options.isAtomic());
        }
        IEventSourcingRepository eventRepository= dbConnection.getRepository();
        IEventStore eventStore=new EventStore(eventRepository);
        Trash trashRepository= (factory != null) ? new Trash(eventStore, factory.trash()) : new Trash(eventStore);
        this.domainRepository=new DomainRepository(eventStore, trashRepository);
        IBatchRepository batchStoreRepository = dbConnection instanceof IBatchRepositoryProvider
                ? ((IBatchRepositoryProvider) dbConnection).getBatchRepository() : null;
        this.batchRepository=new BatchRepository<>(domainRepository,
                batchStoreRepository == null ? null : new BatchStore(batchStoreRepository),
                trashRepository);
        this.storeNotice=new StoreNotice(domainRepository);
        if (factory != null) {
            InfraContext.of(factory).publish();
        }
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

    /**
     * 新链路（推荐，多数据库/单测）：用当前 {@link InfraContext} 的工厂创建
     * 匹配的 {@link com.sooncode.project.core.lookup.ILookupBulkWriter}，
     * 避免 LookupHandler 内部再做 {@code instanceof MongoXxx} 判断。
     */
    public LookupHandler RegisterLookupModel(String packageName, InfraContext context){
        InfraContext ctx = context != null ? context : InfraContext.current();
        IEventSourcingRepository sourceRepo = null;
        com.sooncode.project.core.lookup.ILookupBulkWriter writer = null;
        if (ctx != null) {
            try {
                sourceRepo = ctx.factory().events();
                writer = ctx.factory().lookupWriter(sourceRepo);
            } catch (DomainException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new DomainException("RegisterLookupModel 失败 factory=" + ctx.factoryName() + ": " + e.getMessage(), e);
            } catch (Exception e) {
                throw new DomainException("RegisterLookupModel 失败 factory=" + ctx.factoryName() + ": " + e.getMessage(), e);
            }
        }
        if (sourceRepo == null) {
            return new LookupHandler(packageName, domainRepository);
        }
        // writer 为 null 表示该库不支持批量写，LookupHandler 构造内回退逐条写入，此处透传 null。
        return new LookupHandler(packageName, domainRepository, sourceRepo, writer);
    }
    public IDomainRepository getDomainRepository(){
        return domainRepository;
    }

    public BatchRepository<?> getBatchRepository() {
        return batchRepository;
    }
}
