package com.sooncode.project.core.model;

public class CheckForConcurrencyException extends RuntimeException{
    public CheckForConcurrencyException(String message){
        super(message);
    }
}
