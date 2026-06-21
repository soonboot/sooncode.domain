package com.sooncode.project.core.finder;

import java.util.Collections;
import java.util.List;
import java.util.Map;

class FindWrapper<T> implements IFindWrapper<T>,IAddField<T>,IAggregate{
    private FindHelper fields;
    private String[] groupFields;
    private IFindRepository<T> repository;
    FindWrapper(IFindRepository<T> repository,boolean removeIsDefault){
        fields=new FindHelper(removeIsDefault);
        this.repository=repository;
    }
    @Override
    public IFindWrapper<T> and(String name, Object value) {
        fields.putAnd(name,value);
        return (IFindWrapper<T>)this;
    }

    @Override
    public IFindWrapper<T> and(Map<String, Object> map) {
        this.fields.putAnd(map);
        return (IFindWrapper<T>)this;
    }

    @Override
    public IFindWrapper<T> or(String name, Object value, OType oType) {
        this.fields.putOr(name,value,oType);
        return (IFindWrapper<T>)this;
    }

    @Override
    public IFindWrapper<T> or(String name, Object value) {
        this.fields.putOr(name,value);
        return (IFindWrapper<T>)this;
    }

    @Override
    public IFindWrapper<T> or(Map<String, Object> map) {
        this.fields.putOr(map);
        return (IFindWrapper<T>)this;
    }

    @Override
    public IFindWrapper<T> byField(String name, Object value) {
        this.fields.putAnd(name,value);
        return (IFindWrapper<T>)this;
    }

    @Override
    public IFindWrapper<T> byField(String name, Object value, OType type) {
        this.fields.putAnd(name,value,type);
        return (IFindWrapper<T>)this;
    }

    @Override
    public IFindWrapper<T> byMap(Map<String, Object> map) {
        this.fields.putAnd(map);
        return (IFindWrapper<T>)this;
    }

    @Override
    public IFindWrapper<T> and(String name, Object value,OType oType) {
        this.fields.putAnd(name,value,oType);
        return (IFindWrapper<T>)this;
    }


    @Override
    public T first(Sort sort) {
        return repository.first(fields,sort);
    }

    @Override
    public T first() {
        return repository.first(fields,null);
    }

    @Override
    public List<T> list(Sort sort) {
        return (List<T>)repository.list(fields,sort);
    }

    @Override
    public List<T> list() {
        return (List<T>)repository.list(fields,null);
    }

    @Override
    public Map<String, T> map(String fieldKey) {
        return repository.map(fields,fieldKey);
    }

    @Override
    public List<T> top(int num,Sort sort) {
        return (List<T>)repository.top(fields,num,sort);
    }

    @Override
    public List<T> top(int num) {
        return (List<T>)repository.top(fields,num,null);
    }

    @Override
    public Page<T> page(int pageSize,int pageIndex,Sort sort) {
        return (Page<T>)repository.page(fields,pageSize,pageIndex,sort);
    }

    @Override
    public Page<T> page(int pageSize, int pageIndex) {
        return (Page<T>)repository.page(fields,pageSize,pageIndex,null);
    }

    @Override
    public long count() {
        return repository.count(fields);
    }

    @Override
    public Map<String,Long> count(String[] groupField) {
        return repository.count(fields,groupField);
    }

    @Override
    public <TResult> List<TResult> distinct(String field,Class<TResult> cla)  {
        return repository.distinct(fields,field,cla);
    }

    @Override
    public Map<String,Object> sum(String[] field,String[] groupField) {
        return repository.sum(fields,field,groupField);
    }

    @Override
    public Map<String,Object> avg(String[] field,String[] groupField) {
        return repository.avg(fields,field,groupField);
    }

    @Override
    public Map<String,Object> max(String[] field,String[] groupField) {
        return repository.max(fields,field,groupField);
    }

    @Override
    public Map<String,Object> min(String[] field,String[] groupField) {
        return repository.min(fields,field,groupField);
    }

    @Override
    public IFindWrapper<T> add(String name, Object value) {
        this.fields.putAnd(name,value);
        return this;
    }

    @Override
    public IFindWrapper<T> add(String name, Object value, OType oType) {
        this.fields.putAnd(name,value,oType);
        return this;
    }

    @Override
    public IFindWrapper<T> add(Map<String, Object> map) {
        this.fields.putAnd(map);
        return (IFindWrapper<T>) this;
    }

    @Override
    public IFindAction<Map<String,Object>> group(String[] fields) {
        this.fields.setGroup(fields);
        return (IFindAction<Map<String, Object>>) this;
    }
}
