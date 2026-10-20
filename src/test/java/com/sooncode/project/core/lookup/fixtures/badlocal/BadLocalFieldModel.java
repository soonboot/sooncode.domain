package com.sooncode.project.core.lookup.fixtures.badlocal;

import com.sooncode.project.core.annotations.Lookup;
import com.sooncode.project.core.annotations.LookupModel;
import com.sooncode.project.core.lookup.fixtures.valid.Product;
import com.sooncode.project.core.model.DomainModel;

/** 非法：localField 指向本地模型上不存在的属性。 */
@LookupModel
public class BadLocalFieldModel extends DomainModel<BadLocalFieldModel> {

    @Lookup(fromModel = Product.class, localField = "noSuchLocalField", fromField = "name")
    private String productName;

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
}
