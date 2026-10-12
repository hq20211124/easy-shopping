package com.order.domain;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 订单状态机（8 值枚举，<b>0 基</b>：0待付款/1待发货/2待收货/3已完成/4退款中/5已退款/6已取消/7已关闭）。
 *
 * <p>唯一来源：plans/admin-backend.md 4.3 状态机 + 1.2.2 DDL 注释、plans/android-backend.md 4.3.4、
 * plans/ios-backend.md 4.2.3 逐值一致（AGENTS.md 关键约定：0 基，<b>不是 1 基</b>）；
 * 任何一端新增状态必须多端同步发版，禁止自造状态。</p>
 *
 * <pre>
 * 0待付款 ──支付成功──► 1待发货 ──发货──► 2待收货 ──确认收货──► 3已完成
 *    │                    │                  │                    │
 *    │ 超时/手动关闭       │ 申请退款          │ 申请退款            │ 申请售后
 *    ▼                    ▼                  ▼                    ▼
 * 6已取消(终态)         4退款中 ◄────────────┴────────────────────┘
 *                        │ 退款成功                1/2/3 ──► 4
 *                        ▼
 *                      5已退款 ──归档关闭──► 7已关闭(终态)
 * </pre>
 *
 * <p>cancelled 与 closed 严格区分（4.3 规则 2）：cancelled = 未支付即取消（无资金流）；
 * closed = 已支付后因售后完成或后台关闭而结束（必须有退款或资金处置记录）。</p>
 */
public enum OrderStatus {

    /** 0 待付款（pay_deadline 内未支付 → 超时关单，admin-backend 4.4.4）。 */
    UNPAID(0, "待付款"),
    /** 1 待发货（支付成功，等待拆单/发货）。 */
    PAID(1, "待发货"),
    /** 2 待收货（已发货）。 */
    SHIPPED(2, "待收货"),
    /** 3 已完成（确认收货/自动确认）。 */
    COMPLETED(3, "已完成"),
    /** 4 退款中。 */
    REFUNDING(4, "退款中"),
    /** 5 已退款（业务终态：仅保留归档边 5→7，禁止再进入发货/收货/售后流转）。 */
    REFUNDED(5, "已退款"),
    /** 6 已取消（终态，用户取消/超时未支付，无资金流）。 */
    CANCELLED(6, "已取消"),
    /** 7 已关闭（终态，已支付后关闭，必须有退款或资金处置记录）。 */
    CLOSED(7, "已关闭");

    private static final Map<Integer, OrderStatus> BY_VALUE =
            Stream.of(values()).collect(Collectors.toMap(OrderStatus::getValue, Function.identity()));

    private final int value;
    private final String text;

    OrderStatus(int value, String text) {
        this.value = value;
        this.text = text;
    }

    public int getValue() {
        return value;
    }

    public String getText() {
        return text;
    }

    /** 按 0 基编码取枚举（非法值抛 IllegalArgumentException，调用方按 1001 处理）。 */
    public static OrderStatus of(int value) {
        OrderStatus status = BY_VALUE.get(value);
        if (status == null) {
            throw new IllegalArgumentException("非法订单状态值: " + value);
        }
        return status;
    }
}
