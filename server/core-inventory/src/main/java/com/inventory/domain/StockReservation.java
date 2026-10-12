package com.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 库存预占单（一次下单一笔，行内含该单全部 SKU）。
 *
 * <p>预占以 {@code orderId} 为业务幂等键：release/deduct 对同一 orderId 重复调用只生效一次
 * （admin-backend 4.4.4 第 4 条：释放比扣减更危险，重复释放等于白送库存）。</p>
 *
 * <p>P0 非分片小表主键自增仅为 H2 便利，P1 迁移改雪花（裁决 C13）。</p>
 */
@Entity
@Table(name = "stock_reservation", indexes = {
        @Index(name = "idx_order_status", columnList = "order_id, status"),
        @Index(name = "idx_status_expire", columnList = "status, expire_at")
})
public class StockReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务单号（订单 ID，雪花）。 */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    /** 预占数量（整数，禁止浮点）。 */
    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "status", nullable = false, length = 16)
    private ReservationStatus status = ReservationStatus.ACTIVE;

    /** 预占到期时间（= 订单 pay_deadline，超时由扫描任务释放）。 */
    @Column(name = "expire_at", nullable = false)
    private Instant expireAt;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected StockReservation() {
    }

    public StockReservation(Long orderId, Long skuId, int quantity, Instant expireAt) {
        this.orderId = orderId;
        this.skuId = skuId;
        this.quantity = quantity;
        this.expireAt = expireAt;
    }

    /** 状态流转（调用方保证白名单；@Version 保证并发只成功一次）。 */
    public void moveTo(ReservationStatus target) {
        this.status = target;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public int getQuantity() {
        return quantity;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getExpireAt() {
        return expireAt;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
