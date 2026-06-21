package com.sooncode.project.core.model;

import com.sooncode.project.core.generic.BasicAddEvent;
import com.sooncode.project.core.generic.BasicDeleteEvent;
import com.sooncode.project.core.generic.BasicModifyEvent;

public class test2Model extends DomainModel<test2Model>{
    public void add(String name){
        this.setName(name);
        this.setName2(name+"2");
        this.setTestNumber(1);
        causes(BasicAddEvent.class,this);
    }
    public void modify(String name){
        this.setName(name);
        this.setName2(name+"2");
        this.setTestNumber(testNumber+1);
        causes(BasicModifyEvent.class,this);
    }
    public void delete(){
        causes(BasicDeleteEvent.class);
    }
    private String name;
    private String name2;
    private int testNumber;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName2() {
        return name2;
    }

    public void setName2(String name2) {
        this.name2 = name2;
    }

    public int getTestNumber() {
        return testNumber;
    }

    public void setTestNumber(int testNumber) {
        this.testNumber = testNumber;
    }
}
