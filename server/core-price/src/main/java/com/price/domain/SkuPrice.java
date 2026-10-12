package com.price.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * SKU 价格（价格唯一归属实体）。
 *
 * <p>出处：plans/admin-backend.md 7.3 价格计算（售价 = 商品价 - 商品级优惠 - 优惠券，
 * 金额 DECIMAL(10,2) 禁 Double —— DEV.md 4.3）；调用契约见本模块 README。
 * core-product 的 SPU.minPrice/marketPrice 仅为展示冗余，改价以本表为准，
 * 变更后发 product.updated(changeType=price) 事件供搜索/缓存刷新（P1 专用 price.changed 需先在
 * architecture/04-messaging §7.1 登记）。</p>
 */
@Entity
@Table(name = "sku_price",
        uniqueConstraints = @UniqueConstraint(name = "uk_sku_price_sku", columnNames = "skuId"))
public class SkuPrice {

    @Id
    private Long id;

    /** SKU ID（core-product 的 sku.id，逻辑外键，跨服务不建物理外键） */
    private Long skuId;

    /** 售价（BigDecimal + DECIMAL(10,2)，禁止 Double —— DEV.md 4.3） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** 划线价/市场价（缺省 = price） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal marketPrice;

    @Version
    private Long version;

    private Instant createdAt;

    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getMarketPrice() {
        return marketPrice;
    }

    public void setMarketPrice(BigDecimal marketPrice) {
        this.marketPrice = marketPrice;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
