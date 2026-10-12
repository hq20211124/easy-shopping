package com.payment.web.dto;

import java.math.BigDecimal;

/**
 * 支付状态轮询响应（供各端支付结果页轮询）。
 *
 * @param status pending/paid/refunding/refunded（英文名，前端不得用数字二次推断，对齐 ios-backend 6.x payment_status 口径）
 */
public record PaymentStatusResponse(Long orderId, Long paymentId, Integer channel,
                                    BigDecimal amount, String status, String paidAt) {
}
