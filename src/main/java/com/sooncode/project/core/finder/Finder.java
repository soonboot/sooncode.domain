package com.sooncode.project.core.finder;

import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.IDomainRepository;
import com.sooncode.project.core.monitor.Monitor;
import com.sooncode.project.core.spi.InfraContext;
import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class Finder<T extends DomainModel<T>> implements
    IFind<T>,IFindAction<T>,IAggregate {
    FindWrapper<T> findWrapper;
    IFind<T> find;
    public Finder(Class<T> cla){
        this(cla,true);
    }
    public Finder(Class<T> cla,boolean removeIsDefault){
        init(cla,removeIsDefault);
    }

    private void init(Class<T> cla,boolean removeIsDefault){
        init(cla, resolveRepository(cla), removeIsDefault);
    }

    private void init(Class<T> cla, IFindRepository<T> repository, boolean removeIsDefault){
        findWrapper=new FindWrapper<>(repository,removeIsDefault);
        find=new Find<T>(findWrapper,cla);
    }

    /**
     * DI 构造（推荐）：显式传入查询仓储，多数据库/单测/双库并存请用它。
     * 老构造 {@code new Finder<>(X.class)} 行为不变，内部走 {@link #resolveRepository(Class)}。
     */
    public Finder(Class<T> cla, IFindRepository<T> repository){
        this(cla, repository, true);
    }

    /**
     * DI 构造（推荐）：显式传入查询仓储 + 是否剔除默认值。
     */
    public Finder(Class<T> cla, IFindRepository<T> repository, boolean removeIsDefault){
        if (cla == null) {
            throw new DomainException("Finder 构造失败：cla 不能为 null");
        }
        if (repository == null) {
            throw new DomainException("Finder 构造失败：IFindRepository 不能为空");
        }
        init(cla, repository, removeIsDefault);
    }

    private static <T extends DomainModel<T>> IFindRepository<T> resolveRepository(Class<T> cla) {
        // 新链路优先：InfraContext（多库）。只有 ctx == null 时才回退 Mongo 老链路；
        // ctx 存在时工厂抛的异常必须透出，禁止吞掉后静默混库（P0-1）。
        InfraContext ctx = InfraContext.current();
        if (ctx != null) {
            try {
                IFindRepository<T> repo = ctx.finds(cla);
                if (repo == null) {
                    throw new DomainException("Finder 构造失败：factory=" + ctx.factoryName() + " 返回的 IFindRepository 为 null");
                }
                return repo;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new DomainException("Finder 构造失败 factory=" + ctx.factoryName() + ": " + e.getMessage(), e);
            }
        }
        try {
            Class<?> mongoRepo = Class.forName("com.sooncode.project.core.repository.mongo.FindRepository");
            java.lang.reflect.Constructor<?> c = mongoRepo.getConstructor(Class.class);
            @SuppressWarnings("unchecked")
            IFindRepository<T> repo = (IFindRepository<T>) c.newInstance(cla);
            return repo;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable t = e.getTargetException() != null ? e.getTargetException() : e;
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new DomainException(t.getMessage(), t);
        } catch (Exception e) {
            throw new DomainException("Finder 构造失败：无法解析 IFindRepository，请先调用 new MongoConnection(...) 或 InfraContext.of(...).publish()，或使用 new Finder<>(cla, repo) 显式注入", e);
        }
    }
    @Override
    public T byId(String id){
        return find.byId(id);
    }

    @Override
    public IFindWrapper<T> byField(String name, Object value) {
        return find.byField(name,value);
    }

    @Override
    public IFindWrapper<T> byField(String name, Object value, OType type) {
        return find.byField(name,value,type);
    }

    @Override
    public IFindWrapper<T> byMap(Map<String, Object> map) {
        return (IFindWrapper<T>)find.byMap(map);
    }

    public IFindWrapper<T> andGroup(Consumer<ConditionGroup<T>> sub) {
        return findWrapper.andGroup(sub);
    }

    public IFindWrapper<T> orGroup(Consumer<ConditionGroup<T>> sub) {
        return findWrapper.orGroup(sub);
    }

    @Override
    public IFindWrapper<T> byModel(DomainModel<T> model) {
        return find.byModel(model);
    }

    @Override
    public T first(Sort sort) {
        return findWrapper.first(sort);
    }

    @Override
    public T first() {
        return findWrapper.first();
    }

    @Override
    public List<T> list(Sort sort) {
        return findWrapper.list(sort);
    }

    @Override
    public List<T> list() {
        return findWrapper.list();
    }

    @Override
    public Map<String, T> map(String fieldKey) {
        return findWrapper.map(fieldKey);
    }

    @Override
    public List<T> top(int num, Sort sort) {
        return findWrapper.top(num,sort);
    }

    @Override
    public List<T> top(int num) {
        return findWrapper.top(num);
    }

    @Override
    public Page<T> page(int pageSize, int pageIndex, Sort sort) {
        return findWrapper.page(pageSize,pageIndex,sort);
    }

    @Override
    public Page<T> page(int pageSize, int pageIndex) {
        return findWrapper.page(pageSize,pageIndex);
    }

    @Override
    public long count() {
        return findWrapper.count();
    }

    @Override
    public Map<String,Long> count(String[] groupField) {
        return findWrapper.count(groupField);
    }

    @Override
    public <TResult> List<TResult> distinct(String field,Class<TResult> cla)  {
        return findWrapper.distinct(field,cla);
    }

    @Override
    public Map<String,Object> sum(String[] field,String[] groupField) {
        if(field.length==0){
            throw new DomainException("统计字段不能为空");
        }
        return findWrapper.sum(field,groupField);
    }

    @Override
    public Map<String,Object> avg(String[] field,String[] groupField) {
        if(field.length==0){
            throw new DomainException("统计字段不能为空");
        }
        return findWrapper.avg(field,groupField);
    }

    @Override
    public Map<String,Object> max(String[] field,String[] groupField) {
        if(field.length==0){
            throw new DomainException("统计字段不能为空");
        }
        return findWrapper.max(field,groupField);
    }

    @Override
    public Map<String,Object> min(String[] field,String[] groupField) {
        if(field.length==0){
            throw new DomainException("统计字段不能为空");
        }
        return findWrapper.min(field,groupField);
    }

    @Override
    public IFindAction<Map<String,Object>>  group(String[] fields) {
        return findWrapper.group(fields);
    }
}
