package com.sooncode.project.core.model;

public interface IGenerateReport<T extends DomainModel> {
    public void add(T  obj);
    public void modify(T obj);
    public void delete(T obj);
    public boolean clear();
}
