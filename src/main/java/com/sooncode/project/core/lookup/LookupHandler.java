package com.sooncode.project.core.lookup;

import com.sooncode.project.core.annotations.Lookup;
import com.sooncode.project.core.annotations.LookupModel;
import com.sooncode.project.core.annotations.ModelSnapshot;
import com.sooncode.project.core.finder.Finder;
import com.sooncode.project.core.finder.IFindWrapper;
import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.finder.Sort;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.Entity;
import com.sooncode.project.core.model.IDomainRepository;
import com.sooncode.project.core.model.IEventSourcingRepository;
import com.sooncode.project.core.config.InfraConfig;
import com.sooncode.project.core.monitor.Monitor;
import com.sooncode.project.core.repository.mongo.MongoSingle;
import com.sooncode.project.core.repository.mongo.MongoEventSourcingRepository;
import com.sooncode.project.core.repository.mongo.MongoLookupBulkWriter;
import com.sooncode.project.core.session.SessionManager;
import com.sooncode.project.core.utils.BaseTypeConvert;
import com.sooncode.project.core.utils.ClassUtil;
import com.sooncode.project.core.utils.ReflectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;

/**
 * Lookup 关联处理器（声明式 @LookupModel + @Lookup）。
 * 短期约束（已落地，详见 docs/lookup.md）：
 *   <li>仅支持等值关联：localField 保存 fromModel 的 id（String），fromField 为 fromModel 的可读属性。</li>
 *   <li>扇出建议 &lt; 1w：反向同步按 localField=fromId 查询，首页 page(500, sort=_id) 试探，小扇出(&lt;10)同步，否则异步分页（pageSize=500，可通过 Monitor 调整）。</li>
 *   <li>最终一致性：正/反向同步均直接 saveSnapshot，绕过事件溯源与 CAS，失败仅日志计数，不回滚。</li>
 *   <li>批量/会话内延迟：Batcher 收集阶段不触发 Notice，execute 后正常触发；SessionManager 内延迟到 commit 后。</li>
 *   <li>仅包扫描 classpath 文件系统，jar 内扫描能力取决于 ClassUtil 实现；启动期 fail-fast 校验注解合法性。</li>
 */
public class LookupHandler {

    private static final Logger log = LoggerFactory.getLogger(LookupHandler.class);

    IEventSourcingRepository sourceRepo;
    Monitor monitor;
    IDomainRepository repository;
    ThreadPoolExecutor threadPool = null;
    final ILookupBulkWriter bulkWriter;
    final Map<Class, List<LookupHelper>> lookupMap;
    private static volatile boolean shutdownHookRegistered = false;
    private static final Set<ThreadPoolExecutor> REGISTERED_POOLS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // ===== P0 性能/防环 补充（可通过 Monitor 集中配置）=====
    private static final Sort STABLE_SORT = Sort.ASC("_id");
    private static final int DEFAULT_PAGE_SIZE = 500;
    private static final int DEFAULT_ASYNC_THRESHOLD = 10;
    private static final long DEFAULT_COALESCE_WINDOW_MS = 500L;
    private volatile int pageSize;
    private volatile int asyncThreshold;
    private volatile long coalesceWindowMs;
    private static final ThreadLocal<Set<String>> REENTRANCY_GUARD = ThreadLocal.withInitial(HashSet::new);
    private final ConcurrentHashMap<String, Long> coalesceTimestamps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> inflightKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Entity> pendingCoalesce = new ConcurrentHashMap<>();
    // P0-2 静态共享：避免每 Handler 一个调度线程；shutdown 统一管理
    private static final ScheduledExecutorService SHARED_COALESCE_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r);
        t.setName("lookup-coalesce");
        t.setDaemon(true);
        return t;
    });
    private static final Set<ScheduledExecutorService> REGISTERED_SCHEDULERS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // 实例别名，保持原有字段名兼容
    private final ScheduledExecutorService coalesceScheduler = SHARED_COALESCE_SCHEDULER;

    // ===== 兼容旧构造：委托到新构造，保留 MongoSingle 回退 =====
    public LookupHandler(String packageName, IDomainRepository repository){
        this(packageName, repository, resolveSourceRepoOrFail());
    }

    private static IEventSourcingRepository resolveSourceRepoOrFail() {
        try {
            MongoSingle single = MongoSingle.getInstance();
            if (single == null || single.getRepository() == null) {
                throw new DomainException("LookupHandler: IEventSourcingRepository 未初始化，请通过 new LookupHandler(packageName, repository, sourceRepo) 显式注入，或先初始化 MongoSingle");
            }
            log.warn("[Lookup] 使用 MongoSingle 回退获取 IEventSourcingRepository，建议改为显式注入以解耦 Mongo");
            return single.getRepository();
        } catch (Exception e) {
            throw new DomainException("LookupHandler: 无法解析 IEventSourcingRepository: " + e.getMessage(), e);
        }
    }

    // ===== 推荐构造：显式注入 sourceRepo，彻底解耦 MongoSingle =====
    public LookupHandler(String packageName, IDomainRepository repository, IEventSourcingRepository sourceRepo){
        this(packageName, repository, sourceRepo, createBulkWriter(sourceRepo, repository));
    }

    // ===== 完整构造：可显式注入 ILookupBulkWriter，便于单测/定制批量策略 =====
    public LookupHandler(String packageName, IDomainRepository repository, IEventSourcingRepository sourceRepo, ILookupBulkWriter bulkWriter){
        if (repository == null) throw new DomainException("LookupHandler: IDomainRepository 不能为空");
        if (sourceRepo == null) throw new DomainException("LookupHandler: IEventSourcingRepository 不能为空");
        if (packageName == null || packageName.trim().isEmpty()) throw new DomainException("LookupHandler: packageName 不能为空");
        if (Monitor.instance == null) Monitor.New();
        this.monitor = Monitor.instance;
        this.repository = repository;
        this.sourceRepo = sourceRepo;
        this.bulkWriter = bulkWriter != null ? bulkWriter : createFallbackBulkWriter(repository);
        // P1-1 单一数据源直读 InfraConfig（Monitor 仅做委托兼容），避免 Monitor↔LookupHandler 构造期循环快照
        this.pageSize = InfraConfig.getLookupPageSize() > 0 ? InfraConfig.getLookupPageSize() : DEFAULT_PAGE_SIZE;
        this.asyncThreshold = Math.max(0, InfraConfig.getLookupAsyncThreshold());
        this.coalesceWindowMs = Math.max(0, InfraConfig.getLookupCoalesceWindowMs());
        this.lookupMap = new java.util.concurrent.ConcurrentHashMap<>();
        this.threadPool = createThreadPool();
        registerShutdownHook();
        log.info("[Lookup] 扫描 @LookupModel package={}", packageName);
        List<Class<?>> classList = ClassUtil.getClassListByAnnotation(packageName, LookupModel.class);
        log.info("[Lookup] 发现 {} 个 @LookupModel 类", classList.size());
        if (classList.isEmpty()) {
            log.warn("[Lookup] 未发现任何 @LookupModel，请检查 packageName 是否正确、是否在 classpath 上（当前扫描仅支持 classpath 文件系统，jar 场景需确认 ClassUtil 行为）");
        }
        reBuildSnapshop(classList);
    }

    /**
     * P1-1/P1-5 动态刷新可调优参数（pageSize/asyncThreshold/coalesceWindowMs 直读 InfraConfig，
     * 并同步 bulkWriter.refresh()）。Monitor.set* 后调用本方法即时生效，无需重建 Handler。
     */
    public void refreshConfig() {
        this.pageSize = InfraConfig.getLookupPageSize() > 0 ? InfraConfig.getLookupPageSize() : DEFAULT_PAGE_SIZE;
        this.asyncThreshold = Math.max(0, InfraConfig.getLookupAsyncThreshold());
        this.coalesceWindowMs = Math.max(0, InfraConfig.getLookupCoalesceWindowMs());
        try {
            if (bulkWriter != null) bulkWriter.refresh();
        } catch (Exception e) {
            log.warn("[Lookup] refreshConfig 同步 bulkWriter 失败: {}", e.getMessage(), e);
        }
        log.info("[Lookup] 配置已刷新 pageSize={} asyncThreshold={} coalesceWindowMs={}", pageSize, asyncThreshold, coalesceWindowMs);
    }

    private static ILookupBulkWriter createBulkWriter(IEventSourcingRepository sourceRepo, IDomainRepository repository) {
        // P1-4 优先 instanceof 直接调用 getter（已为 MongoEventSourcingRepository 暴露 getDao/getDbName），反射仅做旧版兼容
        try {
            if (sourceRepo instanceof MongoEventSourcingRepository mongoRepo) {
                Object dao = mongoRepo.getDao();
                Object dbName = mongoRepo.getDbName();
                if (dao instanceof com.sooncode.project.core.repository.mongo.IMongoDBDao && dbName instanceof String) {
                    int bulkBatchSize = InfraConfig.getLookupBulkBatchSize() > 0 ? InfraConfig.getLookupBulkBatchSize() : 500;
                    log.info("[Lookup] 使用 MongoLookupBulkWriter 批量写入 db={} bulkBatchSize={}", dbName, bulkBatchSize);
                    return new MongoLookupBulkWriter((com.sooncode.project.core.repository.mongo.IMongoDBDao) dao, (String) dbName, bulkBatchSize);
                }
                log.warn("[Lookup] MongoEventSourcingRepository 的 dao/dbName 类型不符合预期，回退逐条写入");
            }
        } catch (Exception e) {
            log.warn("[Lookup] 直接读取 MongoEventSourcingRepository 失败，尝试反射兼容: {}", e.getMessage());
            try {
                Class<?> cla = sourceRepo.getClass();
                java.lang.reflect.Field daoField = cla.getDeclaredField("dao");
                java.lang.reflect.Field dbNameField = cla.getDeclaredField("dbName");
                daoField.setAccessible(true);
                dbNameField.setAccessible(true);
                Object dao = daoField.get(sourceRepo);
                Object dbName = dbNameField.get(sourceRepo);
                if (dao instanceof com.sooncode.project.core.repository.mongo.IMongoDBDao && dbName instanceof String) {
                    int bulkBatchSize = InfraConfig.getLookupBulkBatchSize() > 0 ? InfraConfig.getLookupBulkBatchSize() : 500;
                    log.info("[Lookup] 使用 MongoLookupBulkWriter 批量写入 db={} bulkBatchSize={}", dbName, bulkBatchSize);
                    return new MongoLookupBulkWriter((com.sooncode.project.core.repository.mongo.IMongoDBDao) dao, (String) dbName, bulkBatchSize);
                }
            } catch (Exception re) {
                log.warn("[Lookup] 构造 MongoLookupBulkWriter 失败，回退逐条写入: {}", re.getMessage(), re);
            }
        }
        return createFallbackBulkWriter(repository);
    }

    private static ILookupBulkWriter createFallbackBulkWriter(IDomainRepository repository) {
        // P0-5 通过 Supplier 避免捕获旧 repository 引用，兼容 Monitor.ConfigDBConnection 后替换
        final java.util.function.Supplier<IDomainRepository> repoSupplier = () -> {
            if (repository != null) return repository;
            if (Monitor.instance != null && Monitor.instance.getDomainRepository() != null) return Monitor.instance.getDomainRepository();
            return null;
        };
        log.warn("[Lookup] 使用 FallbackBulkWriter（逐条 saveSnapshot），建议在 Mongo 场景传入 MongoLookupBulkWriter 以获得批量性能");
        return new ILookupBulkWriter() {
            @Override
            public int bulkSaveSnapshots(Class<?> modelType, List<Entity> entities) {
                if (entities == null || entities.isEmpty()) return 0;
                IDomainRepository repo = repoSupplier.get();
                if (repo == null) {
                    log.error("[Lookup][FallbackBulk] 无可用 IDomainRepository，批量失败 modelType={}", modelType.getSimpleName());
                    return 0;
                }
                int success = 0;
                for (Entity e : entities) {
                    try {
                        repo.saveSnapshot(e);
                        success++;
                    } catch (Exception ex) {
                        log.error("[Lookup][FallbackBulk] saveSnapshot 失败 modelType={} id={}: {}", modelType.getSimpleName(), e != null ? e.getId() : "null", ex.getMessage(), ex);
                    }
                }
                return success;
            }
        };
    }

    private ThreadPoolExecutor createThreadPool() {
        int core = 2;
        int max = 10;
        long keepAlive = 60L;
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(1000);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            t.setName("lookup-handler-" + t.getId());
            t.setDaemon(true);
            return t;
        };
        ThreadPoolExecutor exec = new ThreadPoolExecutor(core, max, keepAlive, TimeUnit.SECONDS, queue, factory, new ThreadPoolExecutor.CallerRunsPolicy());
        exec.allowCoreThreadTimeOut(true);
        return exec;
    }

    private void registerShutdownHook() {
        REGISTERED_POOLS.add(threadPool);
        REGISTERED_SCHEDULERS.add(SHARED_COALESCE_SCHEDULER);
        if (shutdownHookRegistered) return;
        synchronized (LookupHandler.class) {
            if (shutdownHookRegistered) {
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("[Lookup] JVM shutdown, 关闭 lookup 线程池数量={}, 调度器数量={}", REGISTERED_POOLS.size(), REGISTERED_SCHEDULERS.size());
                for (ThreadPoolExecutor p : REGISTERED_POOLS) {
                    try {
                        p.shutdown();
                        if (!p.awaitTermination(10, TimeUnit.SECONDS)) {
                            p.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        p.shutdownNow();
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        log.warn("[Lookup] 关闭线程池异常: {}", e.getMessage(), e);
                    }
                }
                for (ScheduledExecutorService s : REGISTERED_SCHEDULERS) {
                    try { s.shutdownNow(); } catch (Exception ignore) {}
                }
            }, "lookup-shutdown-hook"));
            shutdownHookRegistered = true;
        }
    }

    public void shutdown() {
        if (threadPool != null) {
            threadPool.shutdown();
            try {
                if (!threadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("[Lookup] 线程池未在 10s 内结束，强制 shutdownNow");
                    threadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                threadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        // P1-3 从 JVM hook 注册表摘除，避免已关闭池长期驻留；P0-2 静态共享调度器仍由 JVM hook 统一关闭，此处仅清理本实例 pending
        REGISTERED_POOLS.remove(threadPool);
        pendingCoalesce.clear();
    }

    public boolean isTerminated() {
        return threadPool == null || threadPool.isTerminated();
    }

    private void reBuildSnapshop(List<Class<?>> classList) {
        for(Class cla : classList){
            Map<Class,LookupHelper> lookups = initLookup(cla);
            if(lookups != null){
                log.info("[Lookup] 检测到 {} 需要全量回填 {} 个 fromModel 关联", cla.getSimpleName(), lookups.size());
                updateEntityThread th = new updateEntityThread(cla, lookups);
                try {
                    threadPool.execute(th);
                } catch (RejectedExecutionException e) {
                    log.warn("[Lookup] 线程池拒绝全量回填任务，改为当前线程同步执行 cla={}", cla.getName(), e);
                    th.run();
                }
            }
        }
        monitorEntity();
    }

    private String getCollectionName(Class<?> cType) {
        String collectionName="";
        if(cType.isAnnotationPresent(ModelSnapshot.class)){
            ModelSnapshot modelSnapshot = cType.getAnnotation(ModelSnapshot.class);
            collectionName = modelSnapshot.value();
            if(collectionName==null|| collectionName.isEmpty()){
                collectionName=modelSnapshot.collectionName();
            }
        }
        return collectionName;
    }

    private Map<Class,LookupHelper> initLookup(Class<?> cla){
        Map snapshot = null;
        try {
            snapshot = sourceRepo.getSnapshotDoc(cla.getName(), getCollectionName(cla));
        } catch (Exception e) {
            log.warn("[Lookup] 获取快照样例失败 cla={} col={}: {}", cla.getName(), getCollectionName(cla), e.getMessage(), e);
        }
        PropertyDescriptor[] properties = ReflectUtils.getBeanSetters(cla);
        boolean needUpdate = false;
        Map<Class,LookupHelper> helperMap = new HashMap<>();
        for(PropertyDescriptor property : properties){
            Field field = findFieldHierarchically(cla, property.getName());
            if (field == null) {
                log.warn("[Lookup] 属性 {} 在类 {} 中找不到对应 Field，跳过", property.getName(), cla.getName());
                continue;
            }
            if(field.isAnnotationPresent(Lookup.class)){
                Lookup lookup = field.getAnnotation(Lookup.class);
                // ---- 启动期 fail-fast 校验 ----
                validateLookupAnnotation(cla, field, lookup);
                LookupHelper helper = helperMap.get(lookup.fromModel());
                if(helper == null) helper = new LookupHelper(cla);
                helper.fromModel = lookup.fromModel();
                helper.addField(lookup.localField(), field);
                helperMap.put(lookup.fromModel(), helper);
                if(snapshot != null && !snapshot.containsKey(property.getName()))
                    needUpdate = true;
            }
        }
        // 校验后登记到全局反向索引（线程安全 computeIfAbsent，避免 get/put 竞态）
        for(Map.Entry<Class,LookupHelper> e : helperMap.entrySet()){
            lookupMap.computeIfAbsent(e.getKey(), k -> new CopyOnWriteArrayList<>()).add(e.getValue());
            log.info("[Lookup] 注册关联 {} --({})--> {} 字段数={} localField(s)={}", e.getValue().localModel.getSimpleName(), e.getKey().getSimpleName(), e.getValue().fields.keySet(), e.getValue().fields.values().stream().mapToInt(List::size).sum(), e.getValue().fields.keySet());
        }
        monitorEntity(cla, helperMap);
        if(needUpdate) return helperMap;
        return null;
    }

    private void validateLookupAnnotation(Class<?> localModel, Field field, Lookup lookup) {
        if (lookup.fromModel() == null) {
            throw new DomainException("[Lookup] " + localModel.getName() + "." + field.getName() + " fromModel 不能为空");
        }
        if (lookup.localField() == null || lookup.localField().trim().isEmpty()) {
            throw new DomainException("[Lookup] " + localModel.getName() + "." + field.getName() + " localField 不能为空");
        }
        if (lookup.fromField() == null || lookup.fromField().trim().isEmpty()) {
            throw new DomainException("[Lookup] " + localModel.getName() + "." + field.getName() + " fromField 不能为空");
        }
        if (!Entity.class.isAssignableFrom(lookup.fromModel())) {
            throw new DomainException("[Lookup] " + localModel.getName() + "." + field.getName() + " fromModel 必须继承 Entity: " + lookup.fromModel().getName());
        }
        if (localModel.equals(lookup.fromModel())) {
            throw new DomainException("[Lookup] " + localModel.getName() + "." + field.getName() + " fromModel 不能与本地模型相同，避免自环");
        }
        // 校验 localField 可读
        PropertyDescriptor localPd = null;
        try {
            localPd = new PropertyDescriptor(lookup.localField(), localModel);
        } catch (Exception e) {
            throw new DomainException("[Lookup] " + localModel.getName() + "." + field.getName() + " localField='" + lookup.localField() + "' 在 " + localModel.getName() + " 中不存在或无 getter/setter", e);
        }
        if (localPd.getReadMethod() == null) {
            throw new DomainException("[Lookup] " + localModel.getName() + "." + field.getName() + " localField='" + lookup.localField() + "' 无读方法");
        }
        // 校验 fromField 可读
        try {
            PropertyDescriptor fromPd = new PropertyDescriptor(lookup.fromField(), lookup.fromModel());
            if (fromPd.getReadMethod() == null) {
                throw new DomainException("[Lookup] " + localModel.getName() + "." + field.getName() + " fromField='" + lookup.fromField() + "' 在 " + lookup.fromModel().getName() + " 中无读方法");
            }
        } catch (Exception e) {
            throw new DomainException("[Lookup] " + localModel.getName() + "." + field.getName() + " fromField='" + lookup.fromField() + "' 在 " + lookup.fromModel().getName() + " 中不存在: " + e.getMessage(), e);
        }
        // 校验目标字段可写
        try {
            PropertyDescriptor targetPd = new PropertyDescriptor(field.getName(), localModel);
            if (targetPd.getWriteMethod() == null) {
                throw new DomainException("[Lookup] " + localModel.getName() + "." + field.getName() + " 目标字段无写方法");
            }
        } catch (Exception e) {
            throw new DomainException("[Lookup] " + localModel.getName() + "." + field.getName() + " 目标字段 PropertyDescriptor 失败: " + e.getMessage(), e);
        }
    }

    private static Field findFieldHierarchically(Class<?> cla, String name) {
        Class<?> cur = cla;
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(name);
                return f;
            } catch (NoSuchFieldException ignore) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }

    //监视实体的添加，触发时更新关联字段
    /**
     * 正向填充：监听本地模型 add/modify，自动用 fromModel 的字段回填。
     * 说明：批量 Batcher 收集阶段 DomainRepository.capture 会拦截 Monitor.Notice，故此处无需 Batcher.current 判断；
     *      Batcher.execute 之后会走正常的 Monitor.Notice，此时 Batcher.CURRENT 已清除，正常触发回填。
     *      SessionManager 存在则延迟到事务提交后执行，避免中间状态污染。
     */
    private void monitorEntity(Class cla, Map<Class,LookupHelper> helperMap){
        if (helperMap == null || helperMap.isEmpty()) return;
        monitor.ListenEntity(cla).add((en)->{
            Entity entity = en.getTargetEntity();
            if(SessionManager.contains(entity)){
                SessionManager.Get(entity).setSessionFunction(()->{
                    updateEntity(cla, entity, helperMap);
                });
            }else{
                updateEntity(cla, entity, helperMap);
            }
        }).modify((en)->{
            Entity entity = en.getTargetEntity();
            if(SessionManager.contains(entity)){
                SessionManager.Get(entity).setSessionFunction(()->{
                    updateEntity(cla, entity, helperMap);
                });
            }else{
                updateEntity(cla, entity, helperMap);
            }
        });
    }

    /**
     * 反向同步：监听 fromModel 的 modify/delete，自动更新所有关联的 localModel 字段。
     * 说明：同正向填充，Batcher 收集阶段不会触发 Notice，execute 后正常触发；此处不再判 Batcher.current。
     * 注意：反向同步当前直接调用 repository.saveSnapshot 绕过 CAS/事件溯源，仅保证最终一致性，
     *      高并发下可能覆盖并发修改；扇出 >1000 的场景建议分页异步（count>=10 时已异步）。
     */
    private void monitorEntity(){
        for(Map.Entry<Class,List<LookupHelper>> entry : lookupMap.entrySet()){
            Class listen = entry.getKey();
            monitor.ListenEntity(listen)
                    .add((en)->{
                        Entity entity = en.getTargetEntity();
                        if(SessionManager.contains(entity)){
                            SessionManager.Get(entity).setSessionFunction(()->{
                                for(LookupHelper helper : entry.getValue()) {
                                    for(Map.Entry<String,List<Field>> localPropertys : helper.fields.entrySet()){
                                        IFindWrapper finder = new Finder<>(helper.localModel).byField(localPropertys.getKey(), entity.getId());
                                        runUpdateSnapshot(entity, finder, helper, localPropertys.getValue(), false);
                                    }
                                }
                            });
                        }else {
                            for(LookupHelper helper : entry.getValue()) {
                                for(Map.Entry<String,List<Field>> localPropertys : helper.fields.entrySet()) {
                                    IFindWrapper finder = new Finder<>(helper.localModel).byField(localPropertys.getKey(), entity.getId());
                                    runUpdateSnapshot(entity, finder, helper, localPropertys.getValue(), false);
                                }
                            }
                        }
                    }).modify((en)->{
                        Entity entity = en.getTargetEntity();
                        if(SessionManager.contains(entity)){
                            SessionManager.Get(entity).setSessionFunction(()->{
                                for(LookupHelper helper : entry.getValue()) {
                                    for(Map.Entry<String,List<Field>> localPropertys : helper.fields.entrySet()){
                                        IFindWrapper finder = new Finder<>(helper.localModel).byField(localPropertys.getKey(), entity.getId());
                                        runUpdateSnapshot(entity, finder, helper, localPropertys.getValue(), false);
                                    }
                                }
                            });
                        }else {
                            for(LookupHelper helper : entry.getValue()) {
                                for(Map.Entry<String,List<Field>> localPropertys : helper.fields.entrySet()) {
                                    IFindWrapper finder = new Finder<>(helper.localModel).byField(localPropertys.getKey(), entity.getId());
                                    runUpdateSnapshot(entity, finder, helper, localPropertys.getValue(), false);
                                }
                            }
                        }
                    }).delete((en)->{
                        Entity entity = en.getTargetEntity();
                        if(SessionManager.contains(entity)){
                            SessionManager.Get(entity).setSessionFunction(()->{
                                for(LookupHelper helper : entry.getValue()) {
                                    for(Map.Entry<String,List<Field>> localPropertys : helper.fields.entrySet()) {
                                        IFindWrapper finder = new Finder<>(helper.localModel).byField(localPropertys.getKey(), entity.getId());
                                        runUpdateSnapshot(entity, finder, helper, localPropertys.getValue(), true);
                                    }
                                }
                            });
                        }else{
                            for(LookupHelper helper : entry.getValue()) {
                                for(Map.Entry<String,List<Field>> localPropertys : helper.fields.entrySet()) {
                                    IFindWrapper finder = new Finder<>(helper.localModel).byField(localPropertys.getKey(), entity.getId());
                                    runUpdateSnapshot(entity, finder, helper, localPropertys.getValue(), true);
                                }
                            }
                        }
                    });
        }
    }

    private String buildDedupKey(Entity fromEntity, LookupHelper helper, List<Field> updateFields, boolean isDelete){
        String localField = "unknown";
        if (updateFields != null && !updateFields.isEmpty()) {
            try { localField = updateFields.get(0).getAnnotation(Lookup.class).localField(); } catch (Exception ignore) {}
        }
        return helper.fromModel.getName() + ":" + fromEntity.getId() + ":" + helper.localModel.getName() + ":" + localField + ":" + isDelete;
    }

    private boolean enterGuard(Entity entity){
        if (entity == null || entity.getId() == null) return true;
        String gid = entity.getClass().getName() + ":" + entity.getId();
        Set<String> set = REENTRANCY_GUARD.get();
        if (set.contains(gid)){
            log.warn("[Lookup] 重入护栏拦截 {} 避免循环更新", gid);
            return false;
        }
        set.add(gid);
        return true;
    }
    private void leaveGuard(Entity entity){
        if (entity == null || entity.getId() == null) return;
        String gid = entity.getClass().getName() + ":" + entity.getId();
        Set<String> set = REENTRANCY_GUARD.get();
        set.remove(gid);
        if (set.isEmpty()) REENTRANCY_GUARD.remove();
    }

    private void runUpdateSnapshot(Entity entity, IFindWrapper finder, LookupHelper helper, List<Field> updateFields, boolean isDelete){
        if (entity == null || entity.getId() == null) return;
        // 去重/合并：高频写入同一 fromId 时窗口内合并，避免线程池风暴
        String dedupKey = buildDedupKey(entity, helper, updateFields, isDelete);
        long now = System.currentTimeMillis();
        Long last = coalesceTimestamps.get(dedupKey);
        if (last != null && now - last < coalesceWindowMs){
            pendingCoalesce.put(dedupKey, entity);
            log.debug("[Lookup] 合并反向同步请求 key={} 窗口内跳过", dedupKey);
            return;
        }
        coalesceTimestamps.put(dedupKey, now);
        // P0-1 TTL：窗口期后自动清理，避免高基数 fromId 无界增长
        final long putTime = now;
        try {
            SHARED_COALESCE_SCHEDULER.schedule(() -> coalesceTimestamps.remove(dedupKey, putTime), coalesceWindowMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignore) {}
        if (inflightKeys.putIfAbsent(dedupKey, Boolean.TRUE) != null){
            pendingCoalesce.put(dedupKey, entity);
            log.debug("[Lookup] 已有任务执行中 合并 key={}", dedupKey);
            return;
        }
        Runnable task = () -> {
            try {
                doRunUpdateSnapshot(entity, finder, helper, updateFields, isDelete, dedupKey);
            } finally {
                inflightKeys.remove(dedupKey);
                Entity pending = pendingCoalesce.remove(dedupKey);
                if (pending != null){
                    // 延迟 COALESCE_WINDOW_MS 后用最新 pending 再触发一次，保证最终一致性
                    long delay = coalesceWindowMs;
                    try {
                        coalesceScheduler.schedule(() -> runUpdateSnapshot(pending, new Finder<>(helper.localModel).byField(updateFields.get(0).getAnnotation(Lookup.class).localField(), pending.getId()), helper, updateFields, isDelete), delay, TimeUnit.MILLISECONDS);
                    } catch (Exception e){
                        log.warn("[Lookup] 调度合并任务失败 key={}: {}", dedupKey, e.getMessage(), e);
                    }
                }
            }
        };
        // 首选异步，线程池关闭或拒绝则同步执行
        if (threadPool.isShutdown()){
            log.warn("[Lookup] 线程池已关闭，改为同步执行 fromId={} key={}", entity.getId(), dedupKey);
            task.run();
            return;
        }
        try {
            // 交给任务内自行做同步/异步分页决策，这里仅做去重后的统一提交
            // 若当前线程是高频写入的请求线程，真正重活在 doRunUpdateSnapshot 内再按阈值分流，避免 count 风暴
            threadPool.execute(task);
        } catch (RejectedExecutionException e){
            log.warn("[Lookup] 线程池拒绝任务，降级同步 key={} fromId={}", dedupKey, entity.getId(), e);
            task.run();
        }
    }

    private void doRunUpdateSnapshot(Entity entity, IFindWrapper finder, LookupHelper helper, List<Field> updateFields, boolean isDelete, String dedupKey){
        // 优化：用首页 page 试探代替 count+list 两次往返；带稳定排序防分页漂移
        Page<Entity> firstPage;
        try {
            firstPage = finder.page(pageSize, 0, STABLE_SORT);
        } catch (Exception e){
            log.error("[Lookup] 反向同步 page 失败 fromId={} localModel={} key={}: {}", entity.getId(), helper.localModel.getSimpleName(), dedupKey, e.getMessage(), e);
            return;
        }
        if (firstPage == null || firstPage.getContent() == null || firstPage.getContent().isEmpty()){
            log.info("[Lookup] 反向同步触发 fromModel={} fromId={} -> localModel={} 命中 0 条 isDelete={} key={}", helper.fromModel.getSimpleName(), entity.getId(), helper.localModel.getSimpleName(), isDelete, dedupKey);
            return;
        }
        List<Entity> firstContent = firstPage.getContent();
        long total = firstPage.getTotalElements();
        // total 可能不准确时以 content 大小为准做阈值判断（小扇出同步）
        long effectiveTotal = total > 0 ? total : firstContent.size();
        log.info("[Lookup] 反向同步触发 fromModel={} fromId={} -> localModel={} 命中 {} 条(首批{}) isDelete={} key={}", helper.fromModel.getSimpleName(), entity.getId(), helper.localModel.getSimpleName(), effectiveTotal, firstContent.size(), isDelete, dedupKey);
        if (effectiveTotal < asyncThreshold && firstContent.size() < pageSize){
            // 小扇出且已全部在首批：直接同步处理，省一次 count/list
            updateSnapshot(entity, firstContent, helper, updateFields, isDelete);
            return;
        }
        // 首批已取到，先处理首批，再决定是否继续分页异步
        updateSnapshot(entity, firstContent, helper, updateFields, isDelete);
        if (firstContent.size() < pageSize){
            return;
        }
        // 大扇出剩余页：复用已有 updateSnapshopThread 分页逻辑，但起点 pageIndex=1 且带稳定排序
        updateSnapshopThread th = new updateSnapshopThread(entity, finder, helper, updateFields, isDelete, 1);
        // 已在线程池线程内，直接同步分页，避免再提交一层任务造成嵌套风暴
        th.run();
    }

    private void updateEntity(Class cla, Entity entity, Map<Class,LookupHelper> helperMap){
        if (!enterGuard(entity)) return;
        boolean anyDirty = false;
        try {
            for(Map.Entry<Class,LookupHelper> map : helperMap.entrySet()){
            LookupHelper helper = map.getValue();
            for(Map.Entry<String,List<Field>> localPropertys : helper.fields.entrySet()) {
                try {
                    PropertyDescriptor localField = new PropertyDescriptor(localPropertys.getKey(), cla);
                    String fromId = null;
                    Entity fromEntity = null;
                    try {
                        if (localField.getReadMethod() != null) {
                            Object val = localField.getReadMethod().invoke(entity);
                            fromId = val == null ? null : val.toString();
                        }
                    } catch (Exception e) {
                        log.error("[Lookup] 读取 localField 失败 localModel={} id={} localField={}: {}", cla.getSimpleName(), entity.getId(), localPropertys.getKey(), e.getMessage(), e);
                        continue;
                    }
                    if (fromId != null && !fromId.isEmpty()) {
                        try {
                            fromEntity = new Finder<>(helper.fromModel).byId(fromId);
                        } catch (Exception e) {
                            log.error("[Lookup] 查询 fromModel 失败 fromModel={} fromId={}: {}", helper.fromModel.getSimpleName(), fromId, e.getMessage(), e);
                        }
                    }
                    boolean groupDirty = false;
                    for (Field field : localPropertys.getValue()) {
                        try {
                            Lookup lookup = field.getAnnotation(Lookup.class);
                            PropertyDescriptor targetProperty = new PropertyDescriptor(field.getName(), helper.localModel);
                            Object targetCurrent = null;
                            if (targetProperty.getReadMethod() != null) {
                                try { targetCurrent = targetProperty.getReadMethod().invoke(entity); } catch (Exception ignore) {}
                            }
                            if (fromEntity == null) {
                                Object defVal = BaseTypeConvert.def(targetProperty.getPropertyType());
                                if (Objects.equals(targetCurrent, defVal)) {
                                    log.debug("[Lookup] 正向跳过无变更 localModel={} id={} field={}", cla.getSimpleName(), entity.getId(), field.getName());
                                    continue;
                                }
                                targetProperty.getWriteMethod().invoke(entity, defVal);
                                groupDirty = true;
                            } else {
                                PropertyDescriptor sourceProperty = new PropertyDescriptor(lookup.fromField(), helper.fromModel);
                                if (targetProperty.getWriteMethod() != null && sourceProperty.getReadMethod() != null) {
                                    Object srcVal = sourceProperty.getReadMethod().invoke(fromEntity);
                                    if (Objects.equals(targetCurrent, srcVal)) {
                                        log.debug("[Lookup] 正向跳过无变更 localModel={} id={} field={} val={}", cla.getSimpleName(), entity.getId(), field.getName(), srcVal);
                                        continue;
                                    }
                                    targetProperty.getWriteMethod().invoke(entity, srcVal);
                                    groupDirty = true;
                                } else {
                                    log.warn("[Lookup] 读写方法缺失 target={} source={} field={}", targetProperty.getWriteMethod(), sourceProperty.getReadMethod(), field.getName());
                                }
                            }
                        } catch (Exception e) {
                            log.error("[Lookup] 正向填充字段失败 localModel={} id={} field={}: {}", cla.getSimpleName(), entity.getId(), field.getName(), e.getMessage(), e);
                        }
                    }
                    // 若本组无变更，继续下一组；最终 saveSnapshot 需按整体是否 dirty 决定
                    if (!groupDirty) continue;
                    anyDirty = true;
                } catch (Exception e) {
                    log.error("[Lookup] 正向填充分组失败 localModel={} id={} localField={}: {}", cla.getSimpleName(), entity.getId(), localPropertys.getKey(), e.getMessage(), e);
                }
            }
        }
        if (!anyDirty){
            log.debug("[Lookup] 正向无变更跳过 saveSnapshot localModel={} id={}", cla.getSimpleName(), entity.getId());
            return;
        }
        try {
            repository.saveSnapshot(entity);
            log.debug("[Lookup] 正向填充后保存快照 localModel={} id={}", cla.getSimpleName(), entity.getId());
        } catch (Exception e) {
            log.error("[Lookup] 保存快照失败 localModel={} id={}: {}", cla.getSimpleName(), entity.getId(), e.getMessage(), e);
        }
        } finally { leaveGuard(entity); }
    }

    private void updateSnapshot(Entity fromEntity, List<Entity> localEntitys, LookupHelper helper, List<Field> updateFields, boolean delete){
        if (localEntitys == null || localEntitys.isEmpty()) {
            log.debug("[Lookup] 反向同步无目标实体 fromId={} localModel={}", fromEntity.getId(), helper.localModel.getSimpleName());
            return;
        }
        int skipped = 0;
        List<Entity> dirty = new ArrayList<>(localEntitys.size());
        int fieldFail = 0;
        for(Entity model : localEntitys){
            if (!enterGuard(model)) { skipped++; continue; }
            // P1-2 脏实体守栏延迟到 bulk 落库后释放：同线程 saveSnapshot→Notice→updateEntity 重入时命中守栏，
            // 跳过重复 byId/回填；干净实体立即释放
            boolean holdGuard = false;
            try {
                boolean modelDirty = false;
                for(Field field : updateFields){
                    try {
                        Lookup lookup = field.getAnnotation(Lookup.class);
                        PropertyDescriptor targetProperty = new PropertyDescriptor(field.getName(), helper.localModel);
                        Object curVal = null;
                        if (targetProperty.getReadMethod() != null) {
                            try { curVal = targetProperty.getReadMethod().invoke(model); } catch (Exception ignore) {}
                        }
                        if(delete){
                            Object defVal = BaseTypeConvert.def(targetProperty.getPropertyType());
                            if (Objects.equals(curVal, defVal)) {
                                log.debug("[Lookup] 反向跳过无变更 localId={} field={}", model.getId(), field.getName());
                                continue;
                            }
                            targetProperty.getWriteMethod().invoke(model, defVal);
                        } else {
                            PropertyDescriptor sourceProperty = new PropertyDescriptor(lookup.fromField(), helper.fromModel);
                            if (targetProperty.getWriteMethod() != null && sourceProperty.getReadMethod() != null) {
                                Object srcVal = sourceProperty.getReadMethod().invoke(fromEntity);
                                if (Objects.equals(curVal, srcVal)) {
                                    log.debug("[Lookup] 反向跳过无变更 localId={} field={} val={}", model.getId(), field.getName(), srcVal);
                                    continue;
                                }
                                targetProperty.getWriteMethod().invoke(model, srcVal);
                            }
                        }
                        modelDirty = true;
                    } catch (Exception e) {
                        log.error("[Lookup] 反向更新字段失败 fromId={} localModel={} localId={} field={}: {}", fromEntity.getId(), helper.localModel.getSimpleName(), model.getId(), field.getName(), e.getMessage(), e);
                        fieldFail++;
                    }
                }
                if (modelDirty) {
                    dirty.add(model);
                    holdGuard = true;
                } else {
                    skipped++;
                }
            } finally { if (!holdGuard) leaveGuard(model); }
        }
        if (dirty.isEmpty()) {
            log.info("[Lookup] 反向同步完成 fromModel={} fromId={} -> localModel={} 成功 0 跳过 {} 失败 {} 总数 {}", helper.fromModel.getSimpleName(), fromEntity.getId(), helper.localModel.getSimpleName(), skipped, fieldFail, localEntitys.size());
            return;
        }
        int success = 0;
        int fail = fieldFail;
        // 独立批量：不走 Batcher，直接 ReplaceOne upsert bulkWrite（BEST_EFFORT）
        // P1-2 脏实体守栏在此统一释放，覆盖 bulk 与逐条 fallback 全程
        try {
            try {
            int bulkSuccess = bulkWriter.bulkSaveSnapshots(helper.localModel, dirty);
            success = bulkSuccess;
            fail += (dirty.size() - bulkSuccess);
            if (fail > fieldFail) {
                log.warn("[Lookup] 反向批量部分失败 fromModel={} fromId={} -> localModel={} 批量成功 {}/{} 字段失败 {}", helper.fromModel.getSimpleName(), fromEntity.getId(), helper.localModel.getSimpleName(), bulkSuccess, dirty.size(), fieldFail);
            }
        } catch (Exception e) {
            log.error("[Lookup] 反向批量写入异常，降级逐条 fromModel={} fromId={} -> localModel={} 批量大小 {}: {}", helper.fromModel.getSimpleName(), fromEntity.getId(), helper.localModel.getSimpleName(), dirty.size(), e.getMessage(), e);
                // 降级逐条，保证最终一致性（守栏仍持有，同线程 Notice 重入会被拦截，不产生重复 byId）
                for (Entity m : dirty) {
                    try {
                        repository.saveSnapshot(m);
                        success++;
                    } catch (Exception ex) {
                        log.error("[Lookup] 反向降级逐条保存失败 localModel={} localId={} fromId={}: {}", helper.localModel.getSimpleName(), m.getId(), fromEntity.getId(), ex.getMessage(), ex);
                        fail++;
                    }
                }
            }
            log.info("[Lookup] 反向同步完成 fromModel={} fromId={} -> localModel={} 成功 {} 跳过 {} 失败 {} 总数 {}", helper.fromModel.getSimpleName(), fromEntity.getId(), helper.localModel.getSimpleName(), success, skipped, fail, localEntitys.size());
        } finally {
            for (Entity m : dirty) leaveGuard(m);
        }
    }

    private class LookupHelper{
        public Class localModel;
        public Class fromModel;
        public Map<String,List<Field>> fields;
        public LookupHelper(Class cla){
            localModel=cla;
            fields=new HashMap<>();
        }
        public void addField(String localProperty, Field field){
            List<Field> fieldList = fields.get(localProperty);
            if(fieldList==null){
                fieldList=new ArrayList<>();
            }
            fieldList.add(field);
            fields.put(localProperty, fieldList);
        }
    }

    class updateSnapshopThread implements Runnable{
        private Entity entity;
        private IFindWrapper finder;
        private LookupHelper helper;
        private boolean isDelete;
        private int pageSize;
        private int pageIndex=0;
        private List<Field> updateFields;
        public updateSnapshopThread(Entity entity,IFindWrapper finder,LookupHelper helper,List<Field> updateFields,boolean isDelete){
            this(entity, finder, helper, updateFields, isDelete, 0);
        }
        public updateSnapshopThread(Entity entity,IFindWrapper finder,LookupHelper helper,List<Field> updateFields,boolean isDelete,int startPage){
            this.entity=entity;
            this.finder=finder;
            this.helper=helper;
            this.isDelete=isDelete;
            this.updateFields=updateFields;
            this.pageIndex=startPage;
            this.pageSize = LookupHandler.this.pageSize;
        }
        @Override
        public void run() {
            while (true){
                Page<Entity> objs;
                try {
                    objs = finder.page(pageSize, pageIndex, STABLE_SORT);
                } catch (Exception e) {
                    log.error("[Lookup] 分页查询失败 fromId={} localModel={} page={}: {}", entity.getId(), helper.localModel.getSimpleName(), pageIndex, e.getMessage(), e);
                    return;
                }
                if (objs == null || objs.getContent() == null || objs.getContent().isEmpty()) return;
                updateSnapshot(entity, objs.getContent(), helper, updateFields, isDelete);
                if(objs.getContent().size() < pageSize){
                    return;
                }
                pageIndex++;
            }
        }
    }

    class updateEntityThread implements Runnable{
        private Class cla;
        private Map<Class,LookupHelper> helperMap;
        private int pageSize;
        private int pageIndex=0;
        public updateEntityThread(Class cla,Map<Class,LookupHelper> helperMap){
            this.cla=cla;
            this.helperMap=helperMap;
            this.pageSize = LookupHandler.this.pageSize;
        }
        @Override
        public void run() {
            int total = 0;
            while (true) {
                Page<Entity> objs;
                try {
                    objs = new Finder<>(cla).page(pageSize, pageIndex, STABLE_SORT);
                } catch (Exception e) {
                    log.error("[Lookup] 全量回填分页查询失败 cla={} page={}: {}", cla.getSimpleName(), pageIndex, e.getMessage(), e);
                    return;
                }
                if (objs == null || objs.getContent() == null || objs.getContent().isEmpty()) {
                    log.info("[Lookup] 全量回填完成 cla={} 共处理 {} 条", cla.getSimpleName(), total);
                    return;
                }
                for(Entity entity : objs.getContent()){
                    updateEntity(cla, entity, helperMap);
                    total++;
                }
                if(objs.getContent().size() < pageSize) {
                    log.info("[Lookup] 全量回填完成 cla={} 共处理 {} 条", cla.getSimpleName(), total);
                    return;
                }
                pageIndex++;
            }
        }
    }
}
