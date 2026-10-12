package com.order.client.dto;

import java.math.BigDecimal;

/**
 * 跨服务客户端 DTO（契约见模块 README）。
 *
 * <p>JSON 命名与被调方响应字段一致（camelCase）；数量 Integer、金额 BigDecimal，禁止浮点。</p>
 */
public final class ClientDtos {

    private ClientDtos() {
    }

    /** core-product {@code GET /api/v1/internal/skus?ids=} 返回的 SKU 视图。 */
    public record SkuView(Long skuId, Long spuId, Long storeId, String name, String image, Boolean onShelf) {
    }

    /** core-price {@code GET /api/v1/price/skus?ids=} 返回的 SKU 价格。 */
    public record PriceView(Long skuId, BigDecimal price) {
    }

    /** core-inventory 预占/校验的行项。 */
    public record InventoryItem(Long skuId, int quantity) {
    }

    /** core-promotion {@code POST /api/v1/coupons/deduct} 返回的核销结果。 */
    public record CouponDeductResult(Long couponId, BigDecimal discount) {
    }
}
