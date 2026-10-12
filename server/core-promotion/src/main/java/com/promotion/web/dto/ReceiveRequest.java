package com.promotion.web.dto;

import jakarta.validation.constraints.NotNull;

/** 领券请求：couponId 为券模板 ID（领取哪张券）。 */
public record ReceiveRequest(@NotNull Long couponId) {
}
