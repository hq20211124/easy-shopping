package com.promotion.web.dto;

import java.time.Instant;

/**
 * 用户券记录响应（我的券列表/领取/核销结果）。
 *
 * <p>字段口径：{@code couponId} 是<b>用户券记录 ID</b>——即 preview/deduct/release 接口里
 * "我的哪张券" 的标识；{@code templateId} 才是券模板 ID（领取 receive 的入参）。</p>
 */
public record CouponRecordResponse(Long couponId, Long templateId, String name, Integer type,
                                   String status, Instant expireAt, Instant receivedAt,
                                   Long usedOrderId, Instant usedTime) {
}
