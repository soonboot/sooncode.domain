package com.sooncode.project.core.model;

import java.io.Serializable;

/**
 * 值对象的基类
 * @param <T>
 */
public interface ValueObject<T> extends Serializable {
    T toValue();
    default void validate() {}
    default boolean isEmpty() {
        return false;
    }
}

