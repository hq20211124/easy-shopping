package com.promotion.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 核销请求（下单/支付链路调用）。
 *
 * @param couponId 用户券记录 ID
 * @param orderId  绑定订单 ID（核销成功后与券一对一绑定，防一券多单）
 */
public record DeductRequest(@NotNull Long couponId, @NotNull Long orderId) {
}
