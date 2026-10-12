package com.promotion.web.dto;

import java.math.BigDecimal;

/**
 * 优惠试算响应。
 *
 * @param eligible    是否可用（不满足门槛/状态不可用时 false，discount=0，reason 说明原因；不抛异常，便于前端展示"不可用原因"）
 * @param discount    优惠金额（服务端计算，HALF_UP 两位小数）
 * @param finalAmount 优惠后应付金额
 */
public record PreviewResponse(Long couponId, Long templateId, String couponName,
                              BigDecimal totalAmount, BigDecimal discount, BigDecimal finalAmount,
                              boolean eligible, String reason) {
}
