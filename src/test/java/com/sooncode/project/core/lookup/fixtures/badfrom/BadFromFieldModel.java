package com.sooncode.project.core.lookup.fixtures.badfrom;

import com.sooncode.project.core.annotations.Lookup;
import com.sooncode.project.core.annotations.LookupModel;
import com.sooncode.project.core.lookup.fixtures.valid.Product;
import com.sooncode.project.core.model.DomainModel;

/** 非法：fromField 在 fromModel 上不存在。 */
@LookupModel
public class BadFromFieldModel extends DomainModel<BadFromFieldModel> {

    private String productId;

    @Lookup(fromModel = Product.class, localField = "productId", fromField = "noSuchFromField")
    private String productName;

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
}
