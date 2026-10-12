package com.promotion.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 优惠试算请求。
 *
 * @param couponId 用户券记录 ID（我持有的一张券，核销/试算以 record 为粒度）
 * @param amount   订单商品金额列表（服务端求和后按门槛/面额试算，客户端不参与计算）
 *
 * <p><b>为什么 {@code amount} 还要 {@code @NotEmpty}</b>：只有 {@code @NotNull} 时空数组 {@code []}
 * 会穿过 Bean 校验、被服务层的业务异常兜住——而 {@code BizException(PARAM_ERROR)} 在全局异常处理器里
 * 映射为 <b>HTTP 200 + code=1001</b>（信封口径），客户端拿到 200 会当成「试算成功但优惠为 0」，
 * 静默按原价下单。空数组本质是参数错误，应在入参层直接 <b>HTTP 400</b>。
 * 服务层仍保留同样的空值判断（internal/服务内调用不经过 Bean 校验）。</p>
 */
public record PreviewRequest(@NotNull Long couponId,
                             @NotEmpty(message = "amount 不能为空") List<@NotNull BigDecimal> amount) {
}