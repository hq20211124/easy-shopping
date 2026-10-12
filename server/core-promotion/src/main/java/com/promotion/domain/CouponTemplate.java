package com.promotion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 优惠券模板（数据模型对齐 plans/admin-backend.md 1.2.2 admin_coupon）。
 *
 * <p>主键为应用层雪花 ID（裁决 C13，禁止自增，见 DEV.md 4.3；P1 迁移时 plans DDL 中的
 * AUTO_INCREMENT 写法一并按 1.2.1 约束修订）。</p>
 */
@Entity
@Table(name = "coupon_template")
public class CouponTemplate {

    /** 满减券：value=面额（元），满 minAmount 减 value。 */
    public static final int TYPE_FULL_CUT = 1;
    /** 折扣券：value=折扣（0<v<10，如 8.5 表示 85 折）。 */
    public static final int TYPE_DISCOUNT = 2;
    /** 无门槛券：等价于满减券且 minAmount=0（P0 preview 与满减同逻辑）。 */
    public static final int TYPE_NO_THRESHOLD = 3;

    /** 主键：雪花 ID。 */
    @Id
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** {@link #TYPE_FULL_CUT} / {@link #TYPE_DISCOUNT} / {@link #TYPE_NO_THRESHOLD}。 */
    @Column(nullable = false)
    private Integer type;

    /** 面额（满减/无门槛）或折扣值（折扣券，0<v<10）。 */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal value;

    /** 最低消费门槛（0=无门槛）。 */
    @Column(name = "min_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal minAmount;

    /** 发放总量。 */
    @Column(name = "total_count", nullable = false)
    private Integer totalCount;

    /** 已领取（防超领以条件更新原子增减，见 repo.reserveStock）。 */
    @Column(name = "received_count", nullable = false)
    private Integer receivedCount = 0;

    /** 已使用（核销 +1，退回 -1）。 */
    @Column(name = "used_count", nullable = false)
    private Integer usedCount = 0;

    /** 每人限领。 */
    @Column(name = "per_limit", nullable = false)
    private Integer perLimit = 1;

    /** 活动开始时间（领取时间窗）。 */
    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    /** 活动结束时间（同时作为用户券的过期时间来源）。 */
    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    /** 1-生效 0-下线。 */
    @Column(nullable = false)
    private Integer status = 1;

    /** 乐观锁（plans DDL 乐观锁字段；并发控制以条件更新 CAS 为准，见 repo）。 */
    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (receivedCount == null) {
            receivedCount = 0;
        }
        if (usedCount == null) {
            usedCount = 0;
        }
        if (status == null) {
            status = 1;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public BigDecimal getMinAmount() {
        return minAmount;
    }

    public void setMinAmount(BigDecimal minAmount) {
        this.minAmount = minAmount;
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public void setTotalCount(Integer totalCount) {
        this.totalCount = totalCount;
    }

    public Integer getReceivedCount() {
        return receivedCount;
    }

    public void setReceivedCount(Integer receivedCount) {
        this.receivedCount = receivedCount;
    }

    public Integer getUsedCount() {
        return usedCount;
    }

    public void setUsedCount(Integer usedCount) {
        this.usedCount = usedCount;
    }

    public Integer getPerLimit() {
        return perLimit;
    }

    public void setPerLimit(Integer perLimit) {
        this.perLimit = perLimit;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
