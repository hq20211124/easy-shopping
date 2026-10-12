package com.payment.web.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 预支付响应（结构对齐 plans/miniprogram-backend.md 8.1.2：paymentId/channel/payType/payParams/expireAt，
 * 增补 amount 供客户端展示——该金额来自订单服务定额，非客户端传入）。
 *
 * @param paymentId 支付单 ID（雪花）
 * @param channel   支付渠道
 * @param amount    应付金额（服务端按订单实付金额定额，裁决 C7 防改价）
 * @param payType   渠道支付类型（mock，P1 接真实渠道后为 wechat_jsapi/alipay_qr 等）
 * @param payParams 调起支付参数（mock 占位；P1 由服务端按渠道组装，客户端只负责调起）
 * @param expireAt  支付过期时间（订单 pay_deadline 派生；到期未支付由订单侧超时关单）
 */
public record PrepayResponse(Long paymentId, Integer channel, BigDecimal amount, String payType,
                             Map<String, String> payParams, Long expireAt) {
}
