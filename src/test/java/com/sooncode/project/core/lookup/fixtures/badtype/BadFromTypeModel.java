package com.sooncode.project.core.lookup.fixtures.badtype;

import com.sooncode.project.core.annotations.Lookup;
import com.sooncode.project.core.annotations.LookupModel;
import com.sooncode.project.core.model.DomainModel;

/** 非法：fromModel 不是 Entity 的子类。 */
@LookupModel
public class BadFromTypeModel extends DomainModel<BadFromTypeModel> {

    private String productId;

    @Lookup(fromModel = String.class, localField = "productId", fromField = "name")
    private String productName;

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
}
