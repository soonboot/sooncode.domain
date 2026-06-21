package com.sooncode.project.core.model;

import com.sooncode.project.core.annotations.Lookup;
import com.sooncode.project.core.annotations.LookupModel;
import com.sooncode.project.core.generic.BasicAddEvent;
import com.sooncode.project.core.generic.BasicDeleteEvent;
import com.sooncode.project.core.generic.BasicModifyEvent;

@LookupModel
public class testModel extends DomainModel<testModel>{
    public void add(String name,String test2Id){
        this.setName(name);
        this.setTest2Id(test2Id);
        causes(BasicAddEvent.class,this);
    }
    public void modify(String name,String test2Id){
        this.setName(name);
        this.setTest2Id(test2Id);
    }
    public void delete(){
        causes(BasicDeleteEvent.class);
    }
    private String name;
    private String test2Id;
    private Integer age;
    private Double heigh;
    private String sex;
    private String type;

    @Lookup(fromModel=test2Model.class,localField="test2Id",fromField="name")
    private String test2Name;
    @Lookup(fromModel=test2Model.class,localField="test2Id",fromField="name2")
    private String test2Name2;
    @Lookup(fromModel = test2Model.class,localField ="test2Id",fromField = "testNumber")
    private int testNumber;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTest2Id() {
        return test2Id;
    }

    public void setTest2Id(String test2Id) {
        this.test2Id = test2Id;
    }

    public String getTest2Name() {
        return test2Name;
    }

    public void setTest2Name(String test2Name) {
        this.test2Name = test2Name;
    }

    public String getTest2Name2() {
        return test2Name2;
    }

    public void setTest2Name2(String test2Name2) {
        this.test2Name2 = test2Name2;
    }

    public int getTestNumber() {
        return testNumber;
    }

    public void setTestNumber(int testNumber) {
        this.testNumber = testNumber;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public Double getHeigh() {
        return heigh;
    }

    public void setHeigh(Double heigh) {
        this.heigh = heigh;
    }

    public String getSex() {
        return sex;
    }

    public void setSex(String sex) {
        this.sex = sex;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
