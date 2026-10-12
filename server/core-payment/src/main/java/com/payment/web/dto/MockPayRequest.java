package com.payment.web.dto;

import jakarta.validation.constraints.NotNull;

/** Mock 支付请求（P0 模拟用户付款成功，走与真实回调相同的处理链）。 */
public record MockPayRequest(@NotNull Long paymentId) {
}
