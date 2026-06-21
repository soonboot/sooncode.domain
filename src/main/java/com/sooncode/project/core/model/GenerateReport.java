package com.sooncode.project.core.model;

import com.sooncode.project.core.annotations.DomainReport;
import com.sooncode.project.core.monitor.Monitor;

import java.lang.reflect.Method;

public class GenerateReport<T extends DomainModel> implements IGenerateReport<T> {
    Monitor monitor = Monitor.instance;
    private IDomainReportRepository repository;
    private Class reportClass;

    private GenerateReport() {
    }

    public GenerateReport(Class tClass, IDomainReportRepository repository) {
        if (!tClass.isAnnotationPresent(DomainReport.class)) return;
        this.repository = repository;
        this.reportClass = tClass;
        DomainReport annotion = (DomainReport) tClass.getAnnotation(DomainReport.class);
        Class modelClass = annotion.Model();
        monitor.ListenEntity(modelClass)
                .add((obj) -> {
                    add((T) obj.getTargetEntity());
                })
                .modify((obj) -> {
                    modify((T) obj.getTargetEntity());
                })
                .delete((obj) -> {
                    delete((T) obj.getTargetEntity());
                });

    }

    private Object getModel(T obj) {
        Object model = null;
        try {
            model = reportClass.newInstance();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        if (IDomainReportModel.class.isAssignableFrom(this.reportClass))
            model = ((IDomainReportModel) model).getModel(obj);
        else
            obj.toEntity(model);
        return model;
    }

    @Override
    public void add(T obj) {
        repository.add(getModel(obj));
    }

    @Override
    public void modify(T obj) {
        repository.modify(getModel(obj));
    }

    @Override
    public void delete(T obj) {
        Object report=null;
        try {
            report=reportClass.newInstance();
            Method method=reportClass.getMethod("setId",String.class);
            method.invoke(report,obj.getId());
        }catch (Exception ex){
        }
        repository.delete(report);
    }

    @Override
    public boolean clear() {
        return repository.clear();
    }
}
