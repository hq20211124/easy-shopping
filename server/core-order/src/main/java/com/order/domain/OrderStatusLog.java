package com.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 订单状态流水表（order_status_log，plans/ios-backend.md 1.2.1）。
 *
 * <p>每次状态变更必写一条（from/to/操作人/原因），详情页 timeline 只读日志不做状态推算；
 * 同时作为对账与客服排查依据（admin-backend 4.3 规则 3）。</p>
 *
 * <p>operator_type 取值按 ios-backend 1.2.1 DDL：1-用户 2-商家 3-系统 4-客服。</p>
 */
@Entity
@Table(name = "order_status_log", indexes = @Index(name = "idx_status_log_order_id", columnList = "order_id"))
public class OrderStatusLog {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_no", nullable = false, length = 32)
    private String orderNo;

    /** 流入状态（0 基 8 值；NULL 仅出现在建单初始流水，用 -1 表示「无（新建）」）。 */
    @Column(name = "from_status", nullable = false)
    private int fromStatus;

    @Column(name = "to_status", nullable = false)
    private int toStatus;

    /** 1-用户 2-商家 3-系统 4-客服。 */
    @Column(name = "operator_type", nullable = false)
    private int operatorType;

    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "reason", length = 256)
    private String reason = "";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected OrderStatusLog() {
    }

    /** 建单初始流水（无流入状态，fromStatus 记 -1）。 */
    public static OrderStatusLog created(Long logId, OrderMain order) {
        return new OrderStatusLog(logId, order.getId(), order.getOrderNo(), -1,
                OrderStatus.UNPAID.getValue(), Operator.system(), "订单创建");
    }

    /** 状态流水（主键雪花，由调用方生成后传入）。 */
    public static OrderStatusLog create(Long logId, Long orderId, String orderNo, int fromStatus, int toStatus,
                                        Operator operator, String reason) {
        return new OrderStatusLog(logId, orderId, orderNo, fromStatus, toStatus, operator, reason);
    }

    private OrderStatusLog(Long id, Long orderId, String orderNo, int fromStatus, int toStatus,
                           Operator operator, String reason) {
        this.id = id;
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.operatorType = operator.type();
        this.operatorId = operator.id();
        this.reason = reason == null ? "" : reason;
    }

    /** 流水操作人（type 取值见类注释）。 */
    public record Operator(int type, Long id) {
        public static Operator user(Long userId) {
            return new Operator(1, userId);
        }

        public static Operator admin(Long adminId) {
            return new Operator(2, adminId);
        }

        public static Operator system() {
            return new Operator(3, null);
        }
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public int getFromStatus() {
        return fromStatus;
    }

    public int getToStatus() {
        return toStatus;
    }

    public int getOperatorType() {
        return operatorType;
    }

    public Long getOperatorId() {
        return operatorId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
