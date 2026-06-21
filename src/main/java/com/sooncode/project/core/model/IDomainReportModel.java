package com.sooncode.project.core.model;

public interface IDomainReportModel<T extends DomainModel> {
    Object getModel(T entity);
}
