package com.easyshopping.common.event;

/**
 * 全平台 30 个领域事件名（唯一权威注册表：architecture/04-messaging.md §7.1）。
 *
 * <p>纪律：事件名一个不改；新增事件必须先在 04-messaging §7.1 登记（沿用 admin-backend 1.4.1 的登记纪律）。
 * 多文件同名事件已收敛到本清单：{@code order.status.changed} 为 {@code OrderStatusChangedEvent} 的定名。</p>
 */
public final class DomainEventNames {

    private DomainEventNames() {
    }

    // ---- 订单域 ----
    public static final String ORDER_CREATED = "order.created";
    public static final String ORDER_PAID = "order.paid";
    public static final String ORDER_REFUNDED = "order.refunded";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String ORDER_REFUND_APPLIED = "order.refund.applied";
    /** 定时消息：到期消费关单，pay_deadline 扫库兜底（admin-backend 4.4.4）。 */
    public static final String ORDER_TIMEOUT_CHECK = "order.timeout.check";
    /** 顺序消息（orderId Hash），订单状态机唯一出入口。 */
    public static final String ORDER_STATUS_CHANGED = "order.status.changed";

    // ---- 支付域 ----
    /** 事务消息，资金事件，禁止静默进 DLQ。 */
    public static final String PAYMENT_SUCCESS = "payment.success";
    public static final String PAYMENT_FAILED = "payment.failed";
    /** 事务消息：退款出账。 */
    public static final String PAYMENT_REFUND = "payment.refund";

    // ---- 商品域 ----
    public static final String PRODUCT_CREATED = "product.created";
    public static final String PRODUCT_UPDATED = "product.updated";
    public static final String PRODUCT_DELETED = "product.deleted";
    /** 店铺冻结强制下架。 */
    public static final String PRODUCT_OFF_SHELF_BY_STORE = "product.off_shelf_by_store";

    // ---- 库存域 ----
    /** 顺序消息（skuId Hash），仅保证流水表顺序。 */
    public static final String INVENTORY_CHANGE = "inventory.change";

    // ---- 用户域 ----
    public static final String MEMBER_REGISTERED = "member.registered";
    /** 合规事件，30 分钟 SLA，删除回执写审计表作为 PIPL 举证。 */
    public static final String USER_DELETED = "user.deleted";

    // ---- 审核/店铺/导出/消息 ----
    public static final String AUDIT_SUBMITTED = "audit.submitted";
    public static final String AUDIT_COMPLETED = "audit.completed";
    public static final String AUDIT_RETURNED = "audit.returned";
    public static final String STORE_AUDITED = "store.audited";
    public static final String EXPORT_REQUEST = "export.request";
    public static final String MESSAGE_SEND = "message.send";

    // ---- 物流/触达/客服 ----
    public static final String LOGISTICS_UPDATE = "logistics.update";
    public static final String PUSH_SEND = "push.send";
    public static final String PUSH_RETRY = "push.retry";
    public static final String CS_MESSAGE = "cs.message";

    // ---- iOS IAP ----
    /** 事务消息。 */
    public static final String IAP_PURCHASED = "iap.purchased";
    public static final String IAP_REFUNDED = "iap.refunded";

    // ---- 风控 ----
    /** 延迟 5s。 */
    public static final String RISK_REVIEW = "risk.review";

    /** 全量事件名（当前 30 个），供校验/注册表自检使用。 */
    public static java.util.List<String> all() {
        return java.util.List.of(
                ORDER_CREATED, ORDER_PAID, ORDER_REFUNDED, ORDER_CANCELLED, ORDER_REFUND_APPLIED,
                ORDER_TIMEOUT_CHECK, ORDER_STATUS_CHANGED,
                PAYMENT_SUCCESS, PAYMENT_FAILED, PAYMENT_REFUND,
                PRODUCT_CREATED, PRODUCT_UPDATED, PRODUCT_DELETED, PRODUCT_OFF_SHELF_BY_STORE,
                INVENTORY_CHANGE,
                MEMBER_REGISTERED, USER_DELETED,
                AUDIT_SUBMITTED, AUDIT_COMPLETED, AUDIT_RETURNED, STORE_AUDITED, EXPORT_REQUEST, MESSAGE_SEND,
                LOGISTICS_UPDATE, PUSH_SEND, PUSH_RETRY, CS_MESSAGE,
                IAP_PURCHASED, IAP_REFUNDED,
                RISK_REVIEW);
    }
}
