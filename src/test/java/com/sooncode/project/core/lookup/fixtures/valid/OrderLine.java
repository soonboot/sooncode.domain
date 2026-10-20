package com.sooncode.project.core.lookup.fixtures.valid;

import com.sooncode.project.core.annotations.Lookup;
import com.sooncode.project.core.annotations.LookupModel;
import com.sooncode.project.core.annotations.ModelSnapshot;
import com.sooncode.project.core.model.DomainModel;

/**
 * 合法的关联本地模型：订单行冗余商品名与价格、供应商名。
 * <p>同一个 localField（productId）下挂了 2 个 @Lookup（name / price），用于验证分组；
 * supplierId 是另一组，指向另一个 fromModel。
 */
@LookupModel
@ModelSnapshot(collectionName = "order_line_snapshot")
public class OrderLine extends DomainModel<OrderLine> {

    private String productId;
    private String supplierId;

    @Lookup(fromModel = Product.class, localField = "productId", fromField = "name")
    private String productName;

    @Lookup(fromModel = Product.class, localField = "productId", fromField = "price")
    private Double productPrice;

    @Lookup(fromModel = Supplier.class, localField = "supplierId", fromField = "name")
    private String supplierName;

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }
    public String getSupplierId() { return supplierId; }
    public void setSupplierId(String supplierId) { this.supplierId = supplierId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public Double getProductPrice() { return productPrice; }
    public void setProductPrice(Double productPrice) { this.productPrice = productPrice; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
}
