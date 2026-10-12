package com.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 商品 SKU（规格明细）。
 *
 * <p>出处：plans/android-backend.md 3.2.3 / plans/ios-backend.md 3.2.2 详情响应中的 skus 元素：
 * skuId、specs（规格键值对）、price、stock（库存归 core-inventory 管，本表不存）、image。</p>
 *
 * <p><b>字段级权限（cost:read）</b>：{@code costPrice} 为敏感成本字段，
 * 仅持有 {@code cost:read} 权限的管理端角色可见（plans/admin-backend.md 2.2.3 / 3.1，
 * 后端脱敏为主）——由 AdminProductController 在序列化前判权置空。</p>
 */
@Entity
@Table(name = "sku", indexes = @Index(name = "idx_sku_spu", columnList = "spuId"))
public class Sku {

    @Id
    private Long id;

    private Long spuId;

    /** SKU 编码（商家侧可读；P0 由服务端生成 sku-<snowflake>，商家可改） */
    @Column(length = 64)
    private String skuCode;

    /** SKU 标题（P0 简化：默认继承 SPU 名称 + 规格摘要） */
    @Column(length = 256)
    private String title;

    /** 规格键值对 JSON（如 {"颜色":"黑色钛金属","容量":"256GB"}），内部批量查询契约字段 */
    @Column(length = 1024)
    private String spec;

    /** SKU 图集，逗号分隔（P0 简化，同 SPU.images） */
    @Column(length = 2048)
    private String images;

    /** 成本价（敏感字段，cost:read 脱敏；金额 BigDecimal 禁 Double，DEV.md 4.3） */
    @Column(precision = 10, scale = 2)
    private BigDecimal costPrice;

    /** SKU 级上下架：1-可售 0-不可售（随 SPU 上下架联动，也可单独下架单个 SKU） */
    private Integer status = 0;

    /** 回收站软删标记（随 SPU 删除联动） */
    private boolean deleted = false;

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

    public Long getSpuId() {
        return spuId;
    }

    public void setSpuId(Long spuId) {
        this.spuId = spuId;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public void setSkuCode(String skuCode) {
        this.skuCode = skuCode;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSpec() {
        return spec;
    }

    public void setSpec(String spec) {
        this.spec = spec;
    }

    public String getImages() {
        return images;
    }

    public void setImages(String images) {
        this.images = images;
    }

    public BigDecimal getCostPrice() {
        return costPrice;
    }

    public void setCostPrice(BigDecimal costPrice) {
        this.costPrice = costPrice;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
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
