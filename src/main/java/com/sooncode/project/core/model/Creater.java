package com.sooncode.project.core.model;

public class Creater {
    private String name;
    private String id;
    private String payload;
    public Creater(){};
    public Creater(String name,String id){
        setName(name);
        setId(id);
    }
    public Creater(String name,String id,String payload){
        setName(name);
        setId(id);
        setPayload(payload);
    }
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }
}
