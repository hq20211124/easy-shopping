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

    /**
     * 操作留痕事由：管理端查看收货人明文手机号（{@code GET /api/admin/orders/{orderNo}/receiver-mobile}）。
     *
     * <p>常量而非字面量：留痕是可检索的合规记录，查询/统计/过滤一律引用本常量，
     * 禁止散落字符串（改文案即漏检）。</p>
     */
    public static final String REASON_VIEW_RECEIVER_MOBILE = "查看收货人明文手机号";

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

    /**
     * 操作留痕：管理端查看收货人<b>明文</b>手机号（非状态流转，故 {@code from == to}）。
     *
     * <p><b>为什么复用状态流水表而不新建审计表</b>：① 审计要求「谁/何时/哪一单/为何」，
     * 本表已具备 order_id/order_no/operator_id/reason/created_at 五要素；
     * ② 不新建表 = P0 无需 DDL 变更，运营/客服在订单详情的 timeline 上就能看到这条查看记录；
     * ③ 新表会与状态流水重复一遍 order_id 索引与查询入口。</p>
     *
     * <p><b>from==to 的读法</b>：查询方以 {@code from != to} 过滤即得「真实状态流转」，
     * 全量读即得操作全留痕；本条不走状态机，不会触发 {@code order.status.changed} 事件。</p>
     *
     * <p><b>已知副作用（登记在案）</b>：买家端 timeline 复用同一张表，
     * 因此用户会看到「查看收货人明文手机号」这条商家侧动作——作为透明化提示保留；
     * 若产品认为不宜对买家展示，需在买家 timeline 查询处按本常量过滤（P1）。</p>
     */
    public static OrderStatusLog receiverMobileViewed(Long logId, OrderMain order, Long adminId) {
        return new OrderStatusLog(logId, order.getId(), order.getOrderNo(),
                order.getOrderStatus(), order.getOrderStatus(),
                Operator.admin(adminId), REASON_VIEW_RECEIVER_MOBILE);
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
