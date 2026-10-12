package com.payment.web.dto;

/** 支付回调响应（P0 信封结构；P1 真实渠道要求裸 "success" 文本时由渠道适配器返回）。 */
public record NotifyResponse(String notifyId, Long paymentId, String result) {

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_DUPLICATE = "DUPLICATE";
}
