package com.payment.service;

import java.math.BigDecimal;

/**
 * 支付回调通知命令（真实渠道回调与 mock 支付共用同一条处理链）。
 *
 * @param notifyId     通知唯一流水号（幂等键；mock 渠道固定 {@code mock:{paymentId}}，重复调用不重复入账）
 * @param paymentId    支付单 ID
 * @param amount       渠道通知金额（第 ③ 步与支付单金额比对）
 * @param thirdPartyNo 渠道流水号
 */
public record NotifyCommand(String notifyId, Long paymentId, BigDecimal amount, String thirdPartyNo) {
}
