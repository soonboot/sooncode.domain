package com.sooncode.project.core.lookup.fixtures.valid;

import com.sooncode.project.core.model.DomainModel;

/** 关联目标模型（fromModel）：被 @Lookup 引用的商品。 */
public class Product extends DomainModel<Product> {
    private String name;
    private Double price;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }
}
