package com.sooncode.project.core.session;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.Entity;


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
        SessionManager.put(entity,this);
        entities.add(entity);
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
        batchOperations.add(operation);
        Entity entity = (Entity) operation.getEntity();
        if (entity != null && !entities.contains(entity)) {
            SessionManager.put(entity, this);
            entities.add(entity);
        }
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
