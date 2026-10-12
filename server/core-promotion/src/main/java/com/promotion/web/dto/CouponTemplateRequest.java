package com.promotion.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 创建/更新券模板请求（管理端；PUT 复用本结构整体覆盖，字段与 plans/admin-backend.md 1.2.2 admin_coupon 对齐）。
 *
 * <p>type=2 折扣券时 value 为折扣（0&lt;value&lt;10，如 8.5 表示 85 折）；其余类型 value 为面额（元）。</p>
 */
public record CouponTemplateRequest(
        @NotBlank String name,
        @NotNull Integer type,
        @NotNull @DecimalMin("0.01") BigDecimal value,
        @DecimalMin("0") BigDecimal minAmount,
        @NotNull @Min(1) Integer totalCount,
        @Min(1) Integer perLimit,
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        Integer status) {
}
