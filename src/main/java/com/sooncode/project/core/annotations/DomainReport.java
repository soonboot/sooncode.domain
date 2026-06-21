package com.sooncode.project.core.annotations;

import com.sooncode.project.core.model.IDomainReportRepository;

import java.lang.annotation.*;

@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DomainReport {
    Class Model();
}
