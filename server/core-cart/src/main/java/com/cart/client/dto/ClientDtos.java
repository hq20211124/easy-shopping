package com.cart.client.dto;

import java.math.BigDecimal;

/**
 * 跨服务客户端 DTO（契约见模块 README；与 core-order 同源，字段 JSON 名与被调方一致）。
 *
 * <p>数量 Integer、金额 BigDecimal，禁止浮点（DEV.md 4）。</p>
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

    /** core-inventory {@code GET /api/v1/inventory/stocks?skuIds=} 返回的库存视图。 */
    public record StockView(Long skuId, Integer availableStock, Integer lockedStock, Integer totalStock) {
    }
}
