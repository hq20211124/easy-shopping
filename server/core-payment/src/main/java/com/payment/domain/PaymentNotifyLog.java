package com.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 支付回调通知日志（回调幂等三步之第 ① 步：唯一键去重）。
 *
 * <p>{@code notify_id} 唯一约束是幂等的唯一事实来源（DB 级，多实例安全，
 * 优于 common 的进程内 {@code @Idempotent} 内存 Map）。同一通知重复投递时
 * 插入唯一键冲突 → 直接返回成功，不再重复入账。</p>
 */
@Entity
@Table(name = "payment_notify_log", uniqueConstraints = {
        @UniqueConstraint(name = "uk_notify_id", columnNames = "notify_id")
})
public class PaymentNotifyLog {

    /** 主键：雪花 ID（裁决 C13）。 */
    @Id
    private Long id;

    /** 通知唯一流水号（渠道侧交易号/通知号；mock 渠道固定为 mock:{paymentId}）。 */
    @Column(name = "notify_id", nullable = false, length = 64)
    private String notifyId;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 渠道通知金额（用于第 ③ 步与支付单金额比对）。 */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "third_party_no", length = 64)
    private String thirdPartyNo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNotifyId() {
        return notifyId;
    }

    public void setNotifyId(String notifyId) {
        this.notifyId = notifyId;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(Long paymentId) {
        this.paymentId = paymentId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getThirdPartyNo() {
        return thirdPartyNo;
    }

    public void setThirdPartyNo(String thirdPartyNo) {
        this.thirdPartyNo = thirdPartyNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
