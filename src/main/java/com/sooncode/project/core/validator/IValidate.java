package com.sooncode.project.core.validator;

import com.sooncode.project.core.monitor.FuncType;

public interface IValidate {
    ModelValidateFailException validate(FuncType funcType);
}
