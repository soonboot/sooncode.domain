package com.sooncode.project.core.validator;

public class Validator {
    public static validator validate(boolean condition,String failMessage){
        return new validator().validate(condition,failMessage);
    }
    public static validator validate(IValidateFunction func,String failMessage){
        return new validator().validate(func.run(),failMessage);
    }
    public static class validator {
        public validator validate(boolean condition, String failMessage) {
            if(!condition)
                throw new ModelValidateFailException(failMessage);
            return validator.this;
        }
        public validator validate(IValidateFunction func,String failMessage){
            return validate(func.run(),failMessage);
        }
    }
}
