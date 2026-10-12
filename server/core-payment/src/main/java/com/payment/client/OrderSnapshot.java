package com.payment.client;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单服务反查快照（只读、跨服务传输用；不落库）。
 *
 * <p>状态取值对齐订单 0 基 8 值状态机（AGENTS.md 唯一事实来源 / admin-backend 4.3）：
 * 0待付款 / 1待发货 / 2待收货 / 3已完成 / 4退款中 / 5已退款 / 6已取消 / 7已关闭。</p>
 *
 * <p><b>金额口径（P0-1 修复，勿回退）</b>：预支付定额只能用 {@link #payAmount()}（应付金额
 * = 商品总额 - 优惠 + 运费）。{@link #totalAmount()} 是<b>优惠前</b>的商品总额，
 * 用它落支付单会让每一笔用券订单多收一个券面额（订单应付 90、支付单 100，差额永久沉淀）。</p>
 *
 * @param orderId     订单 ID
 * @param userId      下单用户（支付侧校验归属，防付他人订单）
 * @param totalAmount 商品总额（优惠前；仅供对账/告警参考，<b>不得</b>用作支付定额）
 * @param payAmount   应付金额（支付域定额唯一口径，裁决 C7 防改价；客户端传入金额一律忽略）
 * @param status      订单状态（0 基 8 值，可能为 null 表示订单服务未返回）
 * @param payDeadline 支付截止时间（订单表唯一事实来源；可能为 null）
 */
public record OrderSnapshot(Long orderId, Long userId, BigDecimal totalAmount, BigDecimal payAmount,
                            Integer status, Instant payDeadline) {

    /** 0 待付款。 */
    public static final int STATUS_UNPAID = 0;
    /** 6 已取消（终态）。 */
    public static final int STATUS_CANCELLED = 6;
    /** 7 已关闭（终态）。 */
    public static final int STATUS_CLOSED = 7;

    /** 订单仍待付款（需通知/补偿 paid）。 */
    public boolean unpaid() {
        return status != null && status == STATUS_UNPAID;
    }

    /** 订单已取消/关闭（支付成功但订单不在待付款 → 竞争场景，告警处理）。 */
    public boolean cancelledOrClosed() {
        return status != null && (status == STATUS_CANCELLED || status == STATUS_CLOSED);
    }
}