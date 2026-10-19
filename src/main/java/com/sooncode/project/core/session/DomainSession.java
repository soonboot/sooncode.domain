package com.sooncode.project.core.session;
import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.annotations.SkipEventSourcing;
import com.sooncode.project.core.batcher.BatchOperation;
import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.Entity;
import com.sooncode.project.core.monitor.FuncType;


import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Function;

public class DomainSession implements ISession{
    private ConcurrentLinkedQueue<ISessionFunction> functions=null;
    private ISessionComplete successFunction;
    private  List<Entity> entities=null;
    // 事务性批量操作：会话内所有 add/save/delete 收集到这里，commit 时同一事务提交
    private List<com.sooncode.project.core.batcher.BatchOperation> batchOperations = new ArrayList<>();
    public DomainSession(){
        functions=new ConcurrentLinkedQueue<>();
        entities=new ArrayList<>();
    }
    @Override
    public void add(Entity entity){
        if (com.sooncode.project.core.batcher.Batcher.current() != null) {
            throw new DomainException("DomainSession 与 Batcher 不能在同一线程内混用，请二选一；实体: " + (entity==null?null:entity.getId()));
        }
        if (entity == null) return;
        if (!entities.contains(entity)) {
            SessionManager.put(entity,this);
            entities.add(entity);
        }
    }
    @Override
    public void setSessionFunction(ISessionFunction function){
        functions.add(function);
    }
    public void addOperation(com.sooncode.project.core.batcher.BatchOperation operation){
        if (operation == null) return;
        if (com.sooncode.project.core.batcher.Batcher.current() != null) {
            throw new DomainException("DomainSession 与 Batcher 不能在同一线程内混用，请二选一；实体: " + operation.getEntity().getId());
        }
        DomainModel entity = operation.getEntity();
        if (entity != null) {
            // 同一实例已有收集：按最终意图合并，而非抛错。
            // 事件本身累积在实体上，这里只需让操作类型反映最终写方法：
            // ADD 后再 MODIFY 仍走新增（事件已累积）；出现 DELETE 以删除为准；
            // 已 DELETE 后再变更属编程错误，抛错。
            for (int i = 0; i < batchOperations.size(); i++) {
                BatchOperation existing = batchOperations.get(i);
                if (existing.getEntity() == entity) {
                    BatchOperation.Type oldType = existing.getType();
                    BatchOperation.Type newType = operation.getType();
                    if (oldType == newType) {
                        return; // 同类型重复收集则幂等忽略
                    }
                    if (oldType == BatchOperation.Type.DELETE) {
                        throw new DomainException("同一会话中已删除实体不能再变更:" + entity.getId());
                    }
                    if (newType == BatchOperation.Type.DELETE) {
                        batchOperations.set(i, operation);
                        return;
                    }
                    if (oldType == BatchOperation.Type.ADD) {
                        return; // 新增实体的后续修改仍随新增一次性写入
                    }
                    // MODIFY 后收到 ADD：以（重新）创建语义为准
                    batchOperations.set(i, operation);
                    return;
                }
            }
            // 不同对象但同类+同ID也视为重复（避免同一会话加载两次同一聚合根后双写）
            String key = operationKey(entity);
            for (BatchOperation existing : batchOperations) {
                if (existing.getEntity() != entity && key.equals(operationKey(existing.getEntity()))) {
                    throw new DomainException("同一会话中实体 ID 不能重复（同一聚合根被加载了两次，请复用同一对象）:" + entity.getId());
                }
            }
        }
        batchOperations.add(operation);
        if (entity != null && !entities.contains((Entity) entity)) {
            SessionManager.put((Entity) entity, this);
            entities.add((Entity) entity);
        }
    }

    // key 分隔符统一为 "-"（类全名 + "-" + id），与 streamNameFor 各实现保持一致，改动需全局同步。
    private static String operationKey(DomainModel entity) {
        if (entity == null) return "";
        return entity.getClass().getName() + "-" + entity.getId();
    }

    // ===== Consumer 便捷 API（对齐 Batcher.run） =====
    /** 实例作用域（类型化）：new DomainSession().runWith(s->{...}) 自动 commit/rollback；
     *  通用写法亦可直接用继承自 ISession 的 run(Consumer<ISession>)：new DomainSession().run(s->...) */
    public void runWith(Consumer<DomainSession> action){
        if (action == null) throw new IllegalArgumentException("action不能为空");
        try {
            action.accept(this);
            commit();
        } catch (RuntimeException | Error e) {
            try { rollback(); } catch (Exception ignore) {}
            throw e;
        } catch (Exception e) {
            try { rollback(); } catch (Exception ignore) {}
            throw new RuntimeException(e);
        }
    }

    public <R> R callWith(Function<DomainSession, R> action){
        if (action == null) throw new IllegalArgumentException("action不能为空");
        try {
            R r = action.apply(this);
            commit();
            return r;
        } catch (RuntimeException | Error e) {
            try { rollback(); } catch (Exception ignore) {}
            throw e;
        } catch (Exception e) {
            try { rollback(); } catch (Exception ignore) {}
            throw new RuntimeException(e);
        }
    }

    /** 静态快捷：DomainSession.run(s->{...}) 无需手动 new/close；runStatic 为兼容别名 */
    public static void run(Consumer<DomainSession> action){
        new DomainSession().runWith(action);
    }
    public static void runStatic(Consumer<DomainSession> action){
        run(action);
    }

    public static <R> R call(Function<DomainSession, R> action){
        return new DomainSession().callWith(action);
    }
    public static <R> R callStatic(Function<DomainSession, R> action){
        return call(action);
    }

    @Override
    public void commit(){
        // 兜底：session.add(e) 登记了、但从未经过 repository.* 收集的 pending 实体，
        // 按待持久化事件的 @EventBoot(StoreFunc) 推断操作类型，避免“commit 静默丢写”。
        collectPendingEntitiesWithoutOperation();
        if (!batchOperations.isEmpty()) {
            List<com.sooncode.project.core.batcher.BatchOperation> toCommit = new ArrayList<>(batchOperations);
            try {
                if (com.sooncode.project.core.monitor.Monitor.instance != null
                        && com.sooncode.project.core.monitor.Monitor.instance.getBatchRepository() != null
                        && com.sooncode.project.core.monitor.Monitor.instance.getBatchRepository().getBatchStore() != null) {
                    com.sooncode.project.core.batcher.BatchOptions opts = new com.sooncode.project.core.batcher.BatchOptions().atomic(com.sooncode.project.core.config.InfraConfig.isAtomic()).failureMode(com.sooncode.project.core.batcher.FailureMode.FAIL_FAST).monitor(true);
                    com.sooncode.project.core.monitor.Monitor.instance.getBatchRepository().persistOperations(toCommit, opts);
                    for (com.sooncode.project.core.batcher.BatchOperation op : toCommit) {
                        com.sooncode.project.core.model.DomainModel dm = op.getEntity();
                        if (dm != null) dm.startVersion = dm.getVersion();
                    }
                } else {
                    if (com.sooncode.project.core.monitor.Monitor.instance == null
                            || com.sooncode.project.core.monitor.Monitor.instance.getDomainRepository() == null) {
                        throw new DomainException("DomainSession 提交失败：Monitor 未初始化，请先调用 Monitor.New() 并配置领域仓储/数据库连接");
                    }
                    // 无 BatchStore 回退：必须先将会话绑定解除，否则 repository.add/save/delete
                    // 会再次命中 SessionManager.contains 而重新延期，导致静默丢写。
                    List<Entity> detached = new ArrayList<>(entities);
                    for (Entity e : detached) {
                        SessionManager.remove(e);
                    }
                    try {
                        for (com.sooncode.project.core.batcher.BatchOperation op : toCommit) {
                            com.sooncode.project.core.model.DomainModel entity = op.getEntity();
                            switch (op.getType()) {
                                case ADD:
                                    com.sooncode.project.core.monitor.Monitor.instance.getDomainRepository().add(entity, null, false);
                                    break;
                                case MODIFY:
                                    com.sooncode.project.core.monitor.Monitor.instance.getDomainRepository().save(entity, null, false);
                                    break;
                                case DELETE:
                                    com.sooncode.project.core.monitor.Monitor.instance.getDomainRepository().delete(entity, null, false);
                                    break;
                            }
                        }
                    } catch (RuntimeException e) {
                        // 失败恢复绑定，以便调用方重试或回滚
                        for (Entity en : detached) {
                            if (!SessionManager.contains(en)) {
                                SessionManager.put(en, this);
                            }
                        }
                        throw e;
                    }
                }
                // 成功后才清理批量队列，失败保留以便重试
                batchOperations.clear();
            } catch (RuntimeException e) {
                // 失败保留 batchOperations/entities/functions 以便调用方重试或回滚，不做清理
                throw e;
            }
        }
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

    /**
     * 为“只 session.add(e)、没走 repository.*”的 pending 实体补收集操作。
     * 操作类型按待持久化事件的 {@code @EventBoot(StoreFunc)} 以最终意图合并推断：
     * DELETE 优先；ADD 之后出现 MODIFY 仍走新增（同一新建实体的创建+修改一次写入）；
     * 忽略 replay/none；无有效语义则跳过。ADD 后 DELETE 等极端混杂按最终意图收敛，
     * 调用方如需严格校验请显式走 repository.* 收集。
     */
    private void collectPendingEntitiesWithoutOperation() {
        if (entities == null || entities.isEmpty()) return;
        List<Entity> snapshot = new ArrayList<>(entities);
        for (Entity entity : snapshot) {
            if (!(entity instanceof DomainModel)) continue;
            DomainModel dm = (DomainModel) entity;
            if (dm.isStored()) continue;
            boolean hasOperation = false;
            for (BatchOperation existing : batchOperations) {
                if (existing.getEntity() == dm) {
                    hasOperation = true;
                    break;
                }
            }
            if (hasOperation) continue;
            List<DomainEvent> pending = dm.getPendingEvents();
            if (pending == null || pending.isEmpty()) continue;
            // 兜底推断同样按最终意图合并：DELETE 优先；ADD 之后出现 MODIFY 仍走新增。
            FuncType last = null;
            boolean hasAdd = false;
            for (DomainEvent event : pending) {
                if (event == null) continue;
                EventBoot ann = event.getClass().getAnnotation(EventBoot.class);
                if (ann == null) continue;
                FuncType ft = ann.StoreFunc();
                if (ft == null || ft == FuncType.replay || ft == FuncType.none) continue;
                if (ft == FuncType.add) hasAdd = true;
                last = ft;
            }
            if (last == null) continue;
            BatchOperation.Type type;
            switch (last) {
                case add: type = BatchOperation.Type.ADD; break;
                case modify: type = hasAdd ? BatchOperation.Type.ADD : BatchOperation.Type.MODIFY; break;
                case delete: type = BatchOperation.Type.DELETE; break;
                default: continue;
            }
            boolean skipES = isSkipEventSourcing(dm);
            Integer expected = (type == BatchOperation.Type.ADD || dm.startVersion == 0) ? null : dm.startVersion;
            batchOperations.add(new BatchOperation(type, dm, null, null, expected, skipES, true));
        }
    }

    private static boolean isSkipEventSourcing(DomainModel entity) {
        if (entity == null) return false;
        SkipEventSourcing ann = entity.getClass().getAnnotation(SkipEventSourcing.class);
        return ann != null && ann.value();
    }

    @Override
    public void rollback(){
        batchOperations.clear();
        functions.clear();
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
