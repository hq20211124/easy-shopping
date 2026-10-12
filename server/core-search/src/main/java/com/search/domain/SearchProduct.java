package com.search.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 搜索冗余商品表（P0 DB LIKE 检索源）。
 *
 * <p>数据来源：core-product 的 {@code GET /api/v1/internal/products}（HTTP 拉取，禁止直连对方 DB ——
 * DEV.md 4.4），由 {@link com.search.service.SearchSyncService} 启动时 + 定时刷新；
 * P1 迁 Elasticsearch 后本表退役（architecture/06-search-recommend.md）。</p>
 */
@Entity
@Table(name = "search_product",
        uniqueConstraints = @UniqueConstraint(name = "uk_search_product_spu", columnNames = "spuId"),
        indexes = @Index(name = "idx_search_product_shelf", columnList = "shelfStatus"))
public class SearchProduct {

    @Id
    private Long id;

    /** SPU ID（core-product 逻辑外键） */
    private Long spuId;

    /** 商品名（LIKE 检索目标，配合 JPA 参数绑定防注入） */
    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String mainImage;

    private Long categoryId;

    /** 冗余最低价（价格区间筛选/排序；BigDecimal 禁 Double —— DEV.md 4.3） */
    @Column(precision = 10, scale = 2)
    private BigDecimal minPrice;

    @Column(precision = 10, scale = 2)
    private BigDecimal marketPrice;

    private Long salesCount = 0L;

    /** 1-上架可见 0-下架（搜索只返回 1） */
    private Integer shelfStatus = 0;

    @Version
    private Long version;

    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSpuId() {
        return spuId;
    }

    public void setSpuId(Long spuId) {
        this.spuId = spuId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMainImage() {
        return mainImage;
    }

    public void setMainImage(String mainImage) {
        this.mainImage = mainImage;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public BigDecimal getMinPrice() {
        return minPrice;
    }

    public void setMinPrice(BigDecimal minPrice) {
        this.minPrice = minPrice;
    }

    public BigDecimal getMarketPrice() {
        return marketPrice;
    }

    public void setMarketPrice(BigDecimal marketPrice) {
        this.marketPrice = marketPrice;
    }

    public Long getSalesCount() {
        return salesCount;
    }

    public void setSalesCount(Long salesCount) {
        this.salesCount = salesCount;
    }

    public Integer getShelfStatus() {
        return shelfStatus;
    }

    public void setShelfStatus(Integer shelfStatus) {
        this.shelfStatus = shelfStatus;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
