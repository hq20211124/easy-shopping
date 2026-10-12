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
 *
 * <p><b>处理状态（P1-1 修复，勿回退）</b>：本行是<b>先独立提交</b>、②③ 后跑的事务外日志，
 * 因此 ③ 金额校验失败回滚 ② 时本行已落库。若不区分处理状态，同一 notifyId 的
 * <b>合法重试</b>（渠道标准语义：金额修正后用同一通知号重投）会被 ① 永久判为「重复」→
 * 返回成功给渠道但订单永远收不到 paid 通知，且 {@code payment.success} 永不发（钱收了不入账）。</p>
 *
 * <ul>
 *   <li>{@link #STATUS_RECEIVED} 已收到，处理中（②③ 未出结果）。只对 PROCESSED（与处理中的 RECEIVED）判重；</li>
 *   <li>{@link #STATUS_PROCESSED} 已入账/已幂等确认——此后同 notifyId 一律判重；</li>
 *   <li>{@link #STATUS_REJECTED} 被拒绝（③ 金额不符 / 终态支付单）——允许同 notifyId 重投重试。</li>
 * </ul>
 */
@Entity
@Table(name = "payment_notify_log", uniqueConstraints = {
        @UniqueConstraint(name = "uk_notify_id", columnNames = "notify_id")
})
public class PaymentNotifyLog {

    /** 已收到，处理中。 */
    public static final String STATUS_RECEIVED = "RECEIVED";
    /** 已入账（状态 CAS + 金额校验通过，或已 paid 的幂等确认）。 */
    public static final String STATUS_PROCESSED = "PROCESSED";
    /** 已拒绝（金额不符 / 终态支付单），允许同 notifyId 修正后重试。 */
    public static final String STATUS_REJECTED = "REJECTED";

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

    /**
     * 处理状态：{@link #STATUS_RECEIVED} / {@link #STATUS_PROCESSED} / {@link #STATUS_REJECTED}。
     * 只有 PROCESSED 才构成「该 notifyId 已消费」的判重依据（见类注释）。
     */
    @Column(name = "process_status", nullable = false, length = 16)
    private String processStatus = STATUS_RECEIVED;

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

    public String getProcessStatus() {
        return processStatus;
    }

    public void setProcessStatus(String processStatus) {
        this.processStatus = processStatus;
    }

    /** 已入账（判重依据，不可再处理）。 */
    public boolean processed() {
        return STATUS_PROCESSED.equals(processStatus);
    }

    /** 拒绝态：允许同 notifyId 修正后重投。 */
    public boolean rejected() {
        return STATUS_REJECTED.equals(processStatus);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
