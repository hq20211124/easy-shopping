package com.payment.web.dto;

import java.math.BigDecimal;

/** 退款响应（幂等：重复退款返回同一终态）。 */
public record RefundResponse(Long orderId, Long paymentId, String status, BigDecimal refundAmount) {
}
