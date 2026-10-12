package com.payment.client;

import java.math.BigDecimal;

/**
 * 订单服务客户端（只调不查库，禁止跨库直连，见 DEV.md 4.4 / admin-backend 1.1.2）。
 *
 * <p>P0 用 RestClient + 静态地址 {@code service.urls.core-order}（连接 1s / 读 3s，仅幂等接口可重试）；
 * P1 换 OpenFeign。接口以 plans 契约为准：</p>
 * <ul>
 *   <li>反查订单快照：{@code GET /api/v1/internal/orders/{orderId}}（服务间直连，无 X-User-Id，
 *       不能走买家端 {@code /api/v1/orders/{id}}，否则被 1002 拦下）</li>
 *   <li>支付成功通知：{@code POST /api/v1/internal/orders/{orderId}/paid}（core-order 的 internal 回调端点；
 *       TODO(core-order 契约)：core-order 若按 plans 以 {@code order.paid} MQ 事件驱动状态机，
 *       本调用可改为纯事件（04-messaging），届时仅保留 HTTP 作为 P0 临时通道）</li>
 * </ul>
 */
public interface OrderClient {

    /** 反查订单快照（金额/用户以此为准）；订单不存在抛 BizException(4001)，服务不可用抛 9999。 */
    OrderSnapshot getOrder(Long orderId);

    /**
     * 支付成功通知订单状态机（unpaid → paid）。
     * 订单侧须幂等（同一目标状态重复通知直接返回成功，admin-backend 4.4.1）。
     */
    void markPaid(Long orderId, Long paymentId, BigDecimal amount);
}
