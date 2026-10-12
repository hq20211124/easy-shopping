package com.payment.domain;

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
 * 支付单（数据模型参考 plans/miniprogram-backend.md 8.1.3 payment_order）。
 *
 * <p>主键为应用层雪花 ID（裁决 C13，禁止自增，见 DEV.md 4.3）。
 * 一个订单同时只允许一个进行中支付单：{@code uk_payment_order(order_id)} 唯一约束保证
 * 并发 prepay 只会创建一单，重复预支付返回原 paymentId。</p>
 */
@Entity
@Table(name = "payment_order", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_order", columnNames = "order_id")
})
public class PaymentOrder {

    /** 待支付。 */
    public static final int STATUS_PENDING = 1;
    /** 已支付。 */
    public static final int STATUS_PAID = 2;
    /** 退款中。 */
    public static final int STATUS_REFUNDING = 3;
    /** 已退款（终态）。 */
    public static final int STATUS_REFUNDED = 4;

    /** 主键：雪花 ID（裁决 C13）。 */
    @Id
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 下单用户（服务端从订单服务反查，不信任客户端）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 支付渠道：1-支付宝 2-微信 3-银行卡(银联) 4-余额（admin-backend 1.2.2 pay_type 字典同源）。 */
    @Column(nullable = false)
    private Integer channel;

    /** 应付金额（服务端按订单实付金额定额，裁决 C7 防改价：客户端传入的金额一律忽略）。 */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /** 状态：{@link #STATUS_PENDING} 等。 */
    @Column(nullable = false)
    private Integer status;

    /** 渠道流水号（mock 渠道为 MOCK- 前缀自造号）。 */
    @Column(name = "third_party_no", length = 64)
    private String thirdPartyNo;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "refund_at")
    private Instant refundAt;

    @Column(name = "refund_reason", length = 255)
    private String refundReason;

    /** 订单侧是否已确认 paid（core-order 回调成功）；false 的 paid 单由对账任务补偿（兜底）。 */
    @Column(name = "order_paid_notified", nullable = false)
    private Boolean orderPaidNotified = false;

    /** 乐观锁（实体级并发控制；状态流转另以 SQL CAS 为准，见 repo）。 */
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
        if (orderPaidNotified == null) {
            orderPaidNotified = false;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    /** 状态英文名（对外轮询口径：pending/paid/refunding/refunded）。 */
    public String statusText() {
        return switch (status == null ? 0 : status) {
            case STATUS_PENDING -> "pending";
            case STATUS_PAID -> "paid";
            case STATUS_REFUNDING -> "refunding";
            case STATUS_REFUNDED -> "refunded";
            default -> "unknown";
        };
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getChannel() {
        return channel;
    }

    public void setChannel(Integer channel) {
        this.channel = channel;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getThirdPartyNo() {
        return thirdPartyNo;
    }

    public void setThirdPartyNo(String thirdPartyNo) {
        this.thirdPartyNo = thirdPartyNo;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }

    public Instant getRefundAt() {
        return refundAt;
    }

    public void setRefundAt(Instant refundAt) {
        this.refundAt = refundAt;
    }

    public String getRefundReason() {
        return refundReason;
    }

    public void setRefundReason(String refundReason) {
        this.refundReason = refundReason;
    }

    public Boolean getOrderPaidNotified() {
        return orderPaidNotified;
    }

    public void setOrderPaidNotified(Boolean orderPaidNotified) {
        this.orderPaidNotified = orderPaidNotified;
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
