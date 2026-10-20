package com.sooncode.project.core.lookup.fixtures.valid;

import com.sooncode.project.core.model.DomainModel;

/** 第二个关联目标模型，用于验证"同一条记录挂多个 fromModel"时的分组行为。 */
public class Supplier extends DomainModel<Supplier> {
    private String name;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
