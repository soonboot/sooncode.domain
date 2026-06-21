package com.sooncode.project.core.annotations;

import java.lang.annotation.*;

@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ModelSnapshot {
    String value() default "";
    String collectionName() default "";
}
