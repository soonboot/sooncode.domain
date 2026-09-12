package com.sooncode.project.core.monitor;

import com.sooncode.project.core.annotations.Lookup;
import com.sooncode.project.core.annotations.LookupModel;
import com.sooncode.project.core.annotations.ModelSnapshot;
import com.sooncode.project.core.finder.Finder;
import com.sooncode.project.core.finder.IFindWrapper;
import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.Entity;
import com.sooncode.project.core.model.IDomainRepository;
import com.sooncode.project.core.model.IEventSourcingRepository;
import com.sooncode.project.core.repository.mongo.MongoSingle;
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
 * <p>
 * <b>短期约束（已落地，详见 docs/lookup.md）：</b>
 * <ul>
 *   <li>仅支持等值关联：localField 保存 fromModel 的 id（String），fromField 为 fromModel 的可读属性。</li>
 *   <li>扇出建议 &lt; 1w：反向同步按 localField=fromId 查询，命中 &gt;=10 条时异步分页（pageSize=100），否则同步。</li>
 *   <li>最终一致性：正/反向同步均直接 saveSnapshot，绕过事件溯源与 CAS，失败仅日志计数，不回滚。</li>
 *   <li>批量/会话内延迟：Batcher 收集阶段不触发 Notice，execute 后正常触发；SessionManager 内延迟到 commit 后。</li>
 *   <li>仅包扫描 classpath 文件系统，jar 内扫描能力取决于 ClassUtil 实现；启动期 fail-fast 校验注解合法性。</li>
 * </ul>
 */
public class LookupHandler {

    private static final Logger log = LoggerFactory.getLogger(LookupHandler.class);

    IEventSourcingRepository sourceRepo;
    Monitor monitor;
    IDomainRepository repository;
    ThreadPoolExecutor threadPool = null;
    final Map<Class, List<LookupHelper>> lookupMap;
    private static volatile boolean shutdownHookRegistered = false;
    private static final Set<ThreadPoolExecutor> REGISTERED_POOLS = Collections.newSetFromMap(new ConcurrentHashMap<>());

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
        if (repository == null) throw new DomainException("LookupHandler: IDomainRepository 不能为空");
        if (sourceRepo == null) throw new DomainException("LookupHandler: IEventSourcingRepository 不能为空");
        if (packageName == null || packageName.trim().isEmpty()) throw new DomainException("LookupHandler: packageName 不能为空");
        if (Monitor.instance == null) throw new DomainException("LookupHandler: Monitor.instance 未初始化，请先执行 Monitor.New() 再注册 Lookup");
        this.monitor = Monitor.instance;
        this.repository = repository;
        this.sourceRepo = sourceRepo;
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
        if (shutdownHookRegistered) return;
        synchronized (LookupHandler.class) {
            if (shutdownHookRegistered) {
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("[Lookup] JVM shutdown, 关闭 lookup 线程池数量={}", REGISTERED_POOLS.size());
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

    private void runUpdateSnapshot(Entity entity, IFindWrapper finder, LookupHelper helper, List<Field> updateFields, boolean isDelete){
        long count;
        try {
            count = finder.count();
        } catch (Exception e) {
            log.error("[Lookup] 反向同步 count 失败 fromId={} localModel={} localField={} : {}", entity.getId(), helper.localModel.getSimpleName(), helper.fields.keySet(), e.getMessage(), e);
            return;
        }
        log.info("[Lookup] 反向同步触发 fromModel={} fromId={} -> localModel={} 命中 {} 条 isDelete={}", helper.fromModel.getSimpleName(), entity.getId(), helper.localModel.getSimpleName(), count, isDelete);
        if(count < 10) {
            List<Entity> objs;
            try {
                objs = finder.list();
            } catch (Exception e) {
                log.error("[Lookup] 反向同步 list 失败 fromId={} localModel={}: {}", entity.getId(), helper.localModel.getSimpleName(), e.getMessage(), e);
                return;
            }
            updateSnapshot(entity, objs, helper, updateFields, isDelete);
            return;
        }
        if (threadPool.isShutdown()) {
            log.warn("[Lookup] 线程池已关闭，改为同步分页执行 fromId={}", entity.getId());
            updateSnapshopThread th = new updateSnapshopThread(entity, finder, helper, updateFields, isDelete);
            th.run();
            return;
        }
        updateSnapshopThread th = new updateSnapshopThread(entity, finder, helper, updateFields, isDelete);
        try {
            threadPool.execute(th);
        } catch (RejectedExecutionException e) {
            log.warn("[Lookup] 线程池拒绝反向同步任务，降级为同步执行 fromId={} localModel={} count={}", entity.getId(), helper.localModel.getSimpleName(), count, e);
            th.run();
        }
    }

    private void updateEntity(Class cla, Entity entity, Map<Class,LookupHelper> helperMap){
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
                    for (Field field : localPropertys.getValue()) {
                        try {
                            Lookup lookup = field.getAnnotation(Lookup.class);
                            PropertyDescriptor targetProperty = new PropertyDescriptor(field.getName(), helper.localModel);
                            if (fromEntity == null) {
                                Object defVal = BaseTypeConvert.def(targetProperty.getPropertyType());
                                targetProperty.getWriteMethod().invoke(entity, defVal);
                            } else {
                                PropertyDescriptor sourceProperty = new PropertyDescriptor(lookup.fromField(), helper.fromModel);
                                if (targetProperty.getWriteMethod() != null && sourceProperty.getReadMethod() != null) {
                                    Object srcVal = sourceProperty.getReadMethod().invoke(fromEntity);
                                    targetProperty.getWriteMethod().invoke(entity, srcVal);
                                } else {
                                    log.warn("[Lookup] 读写方法缺失 target={} source={} field={}", targetProperty.getWriteMethod(), sourceProperty.getReadMethod(), field.getName());
                                }
                            }
                        } catch (Exception e) {
                            log.error("[Lookup] 正向填充字段失败 localModel={} id={} field={}: {}", cla.getSimpleName(), entity.getId(), field.getName(), e.getMessage(), e);
                        }
                    }
                } catch (Exception e) {
                    log.error("[Lookup] 正向填充分组失败 localModel={} id={} localField={}: {}", cla.getSimpleName(), entity.getId(), localPropertys.getKey(), e.getMessage(), e);
                }
            }
        }
        try {
            repository.saveSnapshot(entity);
            log.debug("[Lookup] 正向填充后保存快照 localModel={} id={}", cla.getSimpleName(), entity.getId());
        } catch (Exception e) {
            log.error("[Lookup] 保存快照失败 localModel={} id={}: {}", cla.getSimpleName(), entity.getId(), e.getMessage(), e);
        }
    }

    private void updateSnapshot(Entity fromEntity, List<Entity> localEntitys, LookupHelper helper, List<Field> updateFields, boolean delete){
        if (localEntitys == null || localEntitys.isEmpty()) {
            log.debug("[Lookup] 反向同步无目标实体 fromId={} localModel={}", fromEntity.getId(), helper.localModel.getSimpleName());
            return;
        }
        int success = 0, fail = 0;
        for(Entity model : localEntitys){
            boolean modelDirty = false;
            for(Field field : updateFields){
                try {
                    Lookup lookup = field.getAnnotation(Lookup.class);
                    PropertyDescriptor targetProperty = new PropertyDescriptor(field.getName(), helper.localModel);
                    if(delete){
                        Object defVal = BaseTypeConvert.def(targetProperty.getPropertyType());
                        targetProperty.getWriteMethod().invoke(model, defVal);
                    } else {
                        PropertyDescriptor sourceProperty = new PropertyDescriptor(lookup.fromField(), helper.fromModel);
                        if (targetProperty.getWriteMethod() != null && sourceProperty.getReadMethod() != null) {
                            Object srcVal = sourceProperty.getReadMethod().invoke(fromEntity);
                            targetProperty.getWriteMethod().invoke(model, srcVal);
                        }
                    }
                    modelDirty = true;
                } catch (Exception e) {
                    log.error("[Lookup] 反向更新字段失败 fromId={} localModel={} localId={} field={}: {}", fromEntity.getId(), helper.localModel.getSimpleName(), model.getId(), field.getName(), e.getMessage(), e);
                    fail++;
                }
            }
            if (modelDirty) {
                try {
                    repository.saveSnapshot(model);
                    success++;
                } catch (Exception e) {
                    log.error("[Lookup] 反向同步保存快照失败 localModel={} localId={} fromId={}: {}", helper.localModel.getSimpleName(), model.getId(), fromEntity.getId(), e.getMessage(), e);
                    fail++;
                }
            }
        }
        log.info("[Lookup] 反向同步完成 fromModel={} fromId={} -> localModel={} 成功 {} 失败 {} 总数 {}", helper.fromModel.getSimpleName(), fromEntity.getId(), helper.localModel.getSimpleName(), success, fail, localEntitys.size());
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
        private int pageSize=100;
        private int pageIndex=0;
        private List<Field> updateFields;
        public updateSnapshopThread(Entity entity,IFindWrapper finder,LookupHelper helper,List<Field> updateFields,boolean isDelete){
            this.entity=entity;
            this.finder=finder;
            this.helper=helper;
            this.isDelete=isDelete;
            this.updateFields=updateFields;
        }
        @Override
        public void run() {
            while (true){
                Page<Entity> objs;
                try {
                    objs = finder.page(pageSize, pageIndex);
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
        private int pageSize=100;
        private int pageIndex=0;
        public updateEntityThread(Class cla,Map<Class,LookupHelper> helperMap){
            this.cla=cla;
            this.helperMap=helperMap;
        }
        @Override
        public void run() {
            int total = 0;
            while (true) {
                Page<Entity> objs;
                try {
                    objs = new Finder<>(cla).page(pageSize, pageIndex);
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
