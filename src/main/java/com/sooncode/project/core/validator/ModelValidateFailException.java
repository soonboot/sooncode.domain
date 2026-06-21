package com.sooncode.project.core.validator;

import com.sooncode.project.core.model.DomainException;

public class ModelValidateFailException extends DomainException {
    public ModelValidateFailException(String message) {
        super(message);
    }
}
