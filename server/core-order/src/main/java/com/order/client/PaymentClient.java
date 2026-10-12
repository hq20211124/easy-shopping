package com.order.client;

import java.math.BigDecimal;

/**
 * core-payment 客户端：退款（关单与支付回调竞态的败方补偿）。
 *
 * <p>场景：超时关单 CAS 胜出后收到支付成功回调 → 订单保持已取消，走原路退款；
 * 管理端对已支付未发货订单手工关闭 → 退款 + 资金处置记录（admin-backend 4.3 规则 2）。</p>
 */
public interface PaymentClient {

    /**
     * 原路退款（幂等，以 orderId 为退款单业务键）。
     *
     * <p>TODO(core-payment 契约就绪后)：对齐 {@code POST /api/v1/payments/refund}
     * {@code {orderId, amount, reason}}，重复退款返回原退款单号。</p>
     */
    void refund(Long orderId, BigDecimal amount, String reason);
}
