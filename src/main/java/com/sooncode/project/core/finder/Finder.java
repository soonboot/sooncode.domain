package com.sooncode.project.core.finder;

import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.IDomainRepository;
import com.sooncode.project.core.monitor.Monitor;
import com.sooncode.project.core.repository.mongo.FindRepository;
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
        findWrapper=new FindWrapper<>(new FindRepository<>(cla),removeIsDefault);
        find=new Find<T>(findWrapper,cla);
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
