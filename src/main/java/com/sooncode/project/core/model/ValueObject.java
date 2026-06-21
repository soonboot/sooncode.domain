package com.sooncode.project.core.model;

import java.io.Serializable;

/**
 * 值对象的基类
 * @param <T>
 */
public abstract class ValueObject<T> implements Serializable {
    protected T value;
    public abstract T getValue();
}

