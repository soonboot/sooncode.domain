package com.sooncode.project.core.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * 领域实体的基类
 */
public class Entity implements Serializable {
    private String id;
    public Entity(){
        setId(UUID.randomUUID().toString().trim().replace("-",""));
    }
    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }
}
