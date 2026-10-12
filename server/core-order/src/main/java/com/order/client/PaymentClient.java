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
     * <p>契约（core-payment {@code PaymentController}）：
     * {@code POST /api/v1/payment/refund} body={orderId, reason} → data{orderId, paymentId, status, refundAmount}。
     * 退款金额由支付单全额决定（P0 不支持部分退款），故请求体<b>不带</b> amount。</p>
     *
     * @param orderId 订单 ID（退款业务键）
     * @param amount  本单应付金额（仅用于失败日志与告警，便于人工对账；不参与退款请求体）
     * @param reason  退款原因（写入支付单 refund_reason）
     * @return {@code true}=支付域已受理（退款单已进入 refunded，或此前已是退款终态 → 幂等成功）；
     *         {@code false}=未被受理（网络异常/远端业务错误），调用方<b>不得</b>据此宣告退款成功
     */
    boolean refund(Long orderId, BigDecimal amount, String reason);
}