package com.promotion.web.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 优惠试算请求。
 *
 * @param couponId 用户券记录 ID（我持有的一张券，核销/试算以 record 为粒度）
 * @param amount   订单商品金额列表（服务端求和后按门槛/面额试算，客户端不参与计算）
 */
public record PreviewRequest(@NotNull Long couponId,
                             @NotNull List<@NotNull BigDecimal> amount) {
}
