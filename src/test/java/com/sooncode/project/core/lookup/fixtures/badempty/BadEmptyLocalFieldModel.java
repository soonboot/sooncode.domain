package com.sooncode.project.core.lookup.fixtures.badempty;

import com.sooncode.project.core.annotations.Lookup;
import com.sooncode.project.core.annotations.LookupModel;
import com.sooncode.project.core.lookup.fixtures.valid.Product;
import com.sooncode.project.core.model.DomainModel;

/** 非法：localField 为空串。 */
@LookupModel
public class BadEmptyLocalFieldModel extends DomainModel<BadEmptyLocalFieldModel> {

    private String productId;

    @Lookup(fromModel = Product.class, localField = "", fromField = "name")
    private String productName;

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
}
