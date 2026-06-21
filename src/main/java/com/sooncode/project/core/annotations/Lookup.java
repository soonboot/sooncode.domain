package com.sooncode.project.core.annotations;

import com.sooncode.project.core.model.DomainModel;

import java.lang.annotation.*;

@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface Lookup {
    Class fromModel();
    String localField();
    String fromField();
}
