package com.promotion.web.dto;

import jakarta.validation.constraints.NotNull;

/** 指定用户发放请求（管理端）。 */
public record DistributeRequest(@NotNull Long userId) {
}
