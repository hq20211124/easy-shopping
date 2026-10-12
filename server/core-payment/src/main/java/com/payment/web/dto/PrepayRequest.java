package com.payment.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 预支付请求：只收 orderId + channel。
 *
 * <p><b>安全约束（裁决 C7 防改价）</b>：请求<b>只接受 orderId</b>，不接受金额/用户身份——
 * 金额与用户由服务端按订单服务（core-order）返回定额；客户端即使传了 {@code amount} 等字段，
 * Jackson 反序列化时直接忽略，不会参与任何计算（见 PaymentIntegrationTest#prepayIgnoresClientAmount）。</p>
 *
 * @param orderId 订单 ID
 * @param channel 支付渠道：1-支付宝 2-微信 3-银行卡 4-余额
 */
public record PrepayRequest(@NotNull Long orderId, @NotNull Integer channel) {
}
