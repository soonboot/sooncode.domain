package com.sooncode.project.core.model;

/**
 * 领域异常类
 */
public class DomainException extends RuntimeException{
    private String code;
    private String level;
    public enum LevelEnum{
        S,
        SS,
        SSS
    }
    public DomainException(String message){
        super(message);
    }
    public DomainException(String message,String code){
        super(message);
    }
    public DomainException(String message,String code,String level){
        super(message);
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }
}
