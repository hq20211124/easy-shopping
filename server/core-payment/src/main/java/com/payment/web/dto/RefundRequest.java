package com.payment.web.dto;

import jakarta.validation.constraints.NotNull;

/** 退款请求（供订单关单/售后服务调用；金额为支付单全额，P0 不支持部分退款）。 */
public record RefundRequest(@NotNull Long orderId, String reason) {
}
