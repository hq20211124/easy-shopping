package com.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 库存操作入参 DTO 集合（内部契约，见模块 README）。
 *
 * <p>数量一律 {@code Integer}（禁止浮点）；金额无关。JSON 字段名与契约一致：
 * {@code {orderId, items:[{skuId, quantity}], ttlSeconds}}。</p>
 */
public final class InventoryDtos {

    private InventoryDtos() {
    }

    /** 单个 SKU 的数量请求。 */
    public record InventoryItem(
            @NotNull(message = "skuId 不能为空") Long skuId,
            @Min(value = 1, message = "quantity 必须大于 0") int quantity) {
    }

    /** 预占/校验请求：{@code POST /api/v1/internal/inventory/preview|reserve}。 */
    public record ItemsRequest(
            @Valid List<@NotNull InventoryItem> items) {
    }

    /** 预占请求：orderId + items + TTL（秒，默认与订单 pay_deadline 对齐 30 分钟）。 */
    public record ReserveRequest(
            @NotNull(message = "orderId 不能为空") Long orderId,
            @NotEmpty(message = "items 不能为空") @Valid List<@NotNull InventoryItem> items,
            Long ttlSeconds) {
    }

    /** 释放/扣减请求：{@code POST /api/v1/internal/inventory/release|deduct}。 */
    public record OrderIdRequest(
            @NotNull(message = "orderId 不能为空") Long orderId) {
    }

    /** 预占校验结果（单 SKU）。 */
    public record PreviewItem(Long skuId, int quantity, boolean sufficient, int availableStock) {
    }

    /** 预占校验结果（整体）。 */
    public record PreviewResult(boolean allAvailable, List<PreviewItem> items) {
    }

    /** 预占结果。 */
    public record ReserveResult(Long orderId, boolean reserved, java.time.Instant expireAt) {
    }

    /** 释放/扣减结果。 */
    public record OperationResult(Long orderId, int changed) {
    }

    /** 库存视图：GET /api/v1/inventory/stocks。 */
    public record StockView(Long skuId, int availableStock, int lockedStock, int totalStock) {
    }

    /** 库存初始化（P0 联调用；管理端入库/出库/调整属 admin-inventory 域）。 */
    public record InitStockRequest(
            @NotNull(message = "skuId 不能为空") Long skuId,
            @Min(value = 0, message = "availableStock 不能为负") int availableStock) {
    }
}
