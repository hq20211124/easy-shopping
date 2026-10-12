package com.promotion.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 用户券记录（领取实例；对齐 plans/admin-backend.md 1.2.2 admin_coupon_record）。
 *
 * <p>状态机（与 plans 0/1/2 语义一致）：{@code 0可用 → 1已使用}（核销，条件更新防并发重复核销）、
 * {@code 1已使用 → 0可用}（关单退回）、{@code 0可用 → 2已过期}（定时翻转）。
 * {@code 1已使用/2已过期} 不可再流转（P0；P1 若需「过期券人工恢复」另立接口）。</p>
 *
 * <p>索引 {@code idx_coupon_user(coupon_id, user_id)} 对齐 plans DDL，既服务「我的券/领取记录」
 * 查询，更是<b>每人限领原子判定</b>（{@code reserveStockWithPerLimit} 的 COUNT 子查询）走索引的前提——
 * 没有它，大促并发领取会退化成每请求全表扫描。</p>
 */
@Entity
@Table(name = "coupon_record", indexes = @Index(name = "idx_coupon_user", columnList = "coupon_id,user_id"))
public class CouponRecord {

    /** 可用（未使用）。 */
    public static final int STATUS_AVAILABLE = 0;
    /** 已使用（已核销）。 */
    public static final int STATUS_USED = 1;
    /** 已过期。 */
    public static final int STATUS_EXPIRED = 2;

    /** 主键：雪花 ID。 */
    @Id
    private Long id;

    /** 所属券模板 ID（coupon_template.id）。 */
    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** {@link #STATUS_AVAILABLE} 等。 */
    @Column(nullable = false)
    private Integer status = STATUS_AVAILABLE;

    /** 核销绑定的订单 ID（available → used 时写入，退回时清空）。 */
    @Column(name = "used_order_id")
    private Long usedOrderId;

    @Column(name = "used_time")
    private Instant usedTime;

    /** 过期时间（领取时取模板 end_time 快照）。 */
    @Column(name = "expire_at", nullable = false)
    private Instant expireAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @PrePersist
    void prePersist() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
        if (status == null) {
            status = STATUS_AVAILABLE;
        }
    }

    /** 状态英文名（对外口径 available/used/expired）。 */
    public String statusText() {
        return switch (status == null ? 0 : status) {
            case STATUS_AVAILABLE -> "available";
            case STATUS_USED -> "used";
            case STATUS_EXPIRED -> "expired";
            default -> "unknown";
        };
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCouponId() {
        return couponId;
    }

    public void setCouponId(Long couponId) {
        this.couponId = couponId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getUsedOrderId() {
        return usedOrderId;
    }

    public void setUsedOrderId(Long usedOrderId) {
        this.usedOrderId = usedOrderId;
    }

    public Instant getUsedTime() {
        return usedTime;
    }

    public void setUsedTime(Instant usedTime) {
        this.usedTime = usedTime;
    }

    public Instant getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(Instant expireAt) {
        this.expireAt = expireAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }
}
