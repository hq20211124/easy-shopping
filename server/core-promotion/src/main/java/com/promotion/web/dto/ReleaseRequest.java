package com.promotion.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 关单退回请求（订单取消/支付失败退券）。
 *
 * @param couponId 用户券记录 ID
 * @param orderId  订单 ID（可选；传入时校验与核销绑定订单一致，防止误退他单占用的券）
 */
public record ReleaseRequest(@NotNull Long couponId, Long orderId) {
}
