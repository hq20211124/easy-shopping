package com.price.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * core-price 入参 DTO（jakarta.validation，失败自动 1001 —— DEV.md 4.2 步骤 2）。
 * 金额一律 BigDecimal（禁 Double —— DEV.md 4.3）。
 */
public final class PriceDtos {

    private PriceDtos() {
    }

    /** 单 SKU 改价入参（PUT /api/admin/price/skus/{skuId}）。 */
    public record PriceUpsertInput(
            @NotNull BigDecimal price,
            BigDecimal marketPrice) {
    }

    /** 批量写价条目（内部契约 POST /api/v1/internal/price/skus，core-product 商品创建/编辑时调用）。 */
    public record InternalPriceItem(
            @NotNull Long skuId,
            @NotNull BigDecimal price,
            BigDecimal marketPrice) {
    }

    /** 批量写价入参。 */
    public record InternalPriceBatchInput(List<InternalPriceItem> items) {
    }

    /** 下单试算条目。 */
    public record CalculateItemInput(
            @NotNull Long skuId,
            @NotNull @Min(1) Integer quantity) {
    }

    /** 下单试算入参（POST /api/v1/price/calculate）。couponId 可选，核销逻辑归 core-promotion。 */
    public record CalculateInput(
            @NotNull List<CalculateItemInput> items,
            Long couponId) {
    }
}
