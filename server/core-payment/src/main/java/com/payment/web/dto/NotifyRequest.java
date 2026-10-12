package com.payment.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 支付回调通知（P0 mock 渠道适配的通用结构；P1 接真实渠道时按微信/支付宝各自报文另建适配器，
 * 验签后归一为本结构再进同一处理链）。
 *
 * @param notifyId     通知唯一流水号（幂等键；缺省时用 thirdPartyNo 兜底）
 * @param paymentId    支付单 ID
 * @param amount       渠道通知金额（服务端与支付单金额比对，不符拒绝）
 * @param thirdPartyNo 渠道流水号
 */
public record NotifyRequest(String notifyId,
                            @NotNull Long paymentId,
                            @NotNull @DecimalMin("0.01") BigDecimal amount,
                            @NotBlank String thirdPartyNo) {
}
