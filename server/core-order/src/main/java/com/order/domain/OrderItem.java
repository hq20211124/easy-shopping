package com.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单商品表（order_item，快照固化：下单时价格/名称/规格固化，后续改价不影响历史订单）。
 *
 * <p>明细归属<b>子单</b>（拆单后 order_id/order_no 跟随子单，admin-backend 4.4.2）；
 * 父单不挂明细，买家聚合展示时按子单汇总。</p>
 */
@Entity
@Table(name = "order_item", indexes = {
        @Index(name = "idx_order_id", columnList = "order_id"),
        @Index(name = "idx_order_no", columnList = "order_no"),
        @Index(name = "idx_store_id", columnList = "store_id")
})
public class OrderItem {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_no", nullable = false, length = 32)
    private String orderNo;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "spu_id", nullable = false)
    private Long spuId;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    /** SKU 名称（下单快照）。 */
    @Column(name = "sku_name", nullable = false, length = 256)
    private String skuName;

    @Column(name = "sku_image", length = 512)
    private String skuImage = "";

    /** 下单时单价（core-price 权威计价，忽略客户端金额）。 */
    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** 数量（整数，禁止浮点）。 */
    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /** 行级优惠分摊（保证 Σ行 = 子单优惠，尾差归集到首行）。 */
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected OrderItem() {
    }

    public OrderItem(Long id, Long orderId, String orderNo, Long storeId, Long spuId, Long skuId,
                     String skuName, String skuImage, BigDecimal price, int quantity) {
        this.id = id;
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.storeId = storeId;
        this.spuId = spuId;
        this.skuId = skuId;
        this.skuName = skuName;
        this.skuImage = skuImage;
        this.price = price;
        this.quantity = quantity;
        this.totalAmount = price.multiply(BigDecimal.valueOf(quantity));
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public Long getStoreId() {
        return storeId;
    }

    public Long getSpuId() {
        return spuId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public String getSkuName() {
        return skuName;
    }

    public String getSkuImage() {
        return skuImage;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
