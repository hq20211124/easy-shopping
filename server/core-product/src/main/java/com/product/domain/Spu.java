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
 * 商品 SPU（对外唯一真相字段 = auditStatus）。
 *
 * <p>出处：plans/admin-backend.md 3.2.1 商品接口 / 3.3.2 状态映射；
 * 列表展示字段对齐 plans/android-backend.md 3.1.3、plans/ios-backend.md 3.2.1。</p>
 *
 * <p><b>价格口径</b>：SKU 级价格的唯一归属是 core-price（本服务不冗余 SKU 价格列）；
 * SPU 上的 {@code minPrice}/{@code marketPrice} 是仅供列表/搜索展示的<b>反范式冗余</b>，
 * 由创建/编辑商品时随 SKU 一起写入并经 {@code PriceClient} 同步到 core-price，
 * P1+ 改由 core-price 的 price.changed 事件回写（见 README 跨服务契约）。</p>
 */
@Entity
@Table(name = "spu", indexes = {
        @Index(name = "idx_spu_category", columnList = "categoryId"),
        @Index(name = "idx_spu_audit_shelf", columnList = "auditStatus,shelfStatus")
})
public class Spu {

    @Id
    private Long id;

    /** SPU 编码（商家侧可读编码，P0 由服务端生成 spu-<snowflake>，商家可改） */
    @Column(length = 64)
    private String spuCode;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 256)
    private String subtitle;

    private Long categoryId;

    /** 主图 URL */
    @Column(length = 512)
    private String mainImage;

    /** 图集 URL，逗号分隔（P0 简化；P1 迁 JSON 数组列） */
    @Column(length = 2048)
    private String images;

    /** 视频 URL */
    @Column(length = 512)
    private String video;

    /** 富文本详情 */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** 冗余：SKU 最低价（列表/搜索展示用，BigDecimal 禁 Double，DEV.md 4.3） */
    @Column(precision = 10, scale = 2)
    private BigDecimal minPrice;

    /** 冗余：划线价/市场价（展示用） */
    @Column(precision = 10, scale = 2)
    private BigDecimal marketPrice;

    /** 销量（P0 手工维护字段；P1 由订单事件累计） */
    private Long salesCount = 0L;

    /** 审核状态（对外唯一真相）：0-draft 1-auditing 2-on_shelf 3-rejected，见 {@link AuditStatus} */
    private Integer auditStatus = AuditStatus.DRAFT.getValue();

    /** 上下架：0-下架 1-上架；上架前置条件 = auditStatus=2，见 {@link ShelfStatus} */
    private Integer shelfStatus = ShelfStatus.OFF_SHELF.getValue();

    /** 审核备注（驳回原因 / 退回意见，驳回必填） */
    @Column(length = 512)
    private String auditRemark;

    /** 回收站软删标记（P0 简化：删除进回收站，可 restore 恢复） */
    private boolean deleted = false;

    /** 乐观锁（admin-backend 3.3.2 规则 6：并发审核只有一人成功） */
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

    public String getSpuCode() {
        return spuCode;
    }

    public void setSpuCode(String spuCode) {
        this.spuCode = spuCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getMainImage() {
        return mainImage;
    }

    public void setMainImage(String mainImage) {
        this.mainImage = mainImage;
    }

    public String getImages() {
        return images;
    }

    public void setImages(String images) {
        this.images = images;
    }

    public String getVideo() {
        return video;
    }

    public void setVideo(String video) {
        this.video = video;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
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

    public Integer getAuditStatus() {
        return auditStatus;
    }

    public void setAuditStatus(Integer auditStatus) {
        this.auditStatus = auditStatus;
    }

    public Integer getShelfStatus() {
        return shelfStatus;
    }

    public void setShelfStatus(Integer shelfStatus) {
        this.shelfStatus = shelfStatus;
    }

    public String getAuditRemark() {
        return auditRemark;
    }

    public void setAuditRemark(String auditRemark) {
        this.auditRemark = auditRemark;
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
