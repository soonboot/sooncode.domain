package com.sooncode.project.core.annotations;
import com.sooncode.project.core.monitor.FuncType;

import java.lang.annotation.*;

@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventBoot {
    FuncType StoreFunc();
    String[] Params() default {};
    boolean KeepAll() default false;

}
