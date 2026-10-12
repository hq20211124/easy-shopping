package com.promotion.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** 券模板响应。 */
public record CouponTemplateResponse(Long id, String name, Integer type, BigDecimal value,
                                     BigDecimal minAmount, Integer totalCount, Integer receivedCount,
                                     Integer usedCount, Integer perLimit, Instant startTime,
                                     Instant endTime, Integer status) {
}
