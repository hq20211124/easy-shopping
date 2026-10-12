package com.inventory.web;

import com.easyshopping.common.api.ApiResponse;
import com.inventory.dto.InventoryDtos.InitStockRequest;
import com.inventory.dto.InventoryDtos.ItemsRequest;
import com.inventory.dto.InventoryDtos.OperationResult;
import com.inventory.dto.InventoryDtos.OrderIdRequest;
import com.inventory.dto.InventoryDtos.PreviewResult;
import com.inventory.dto.InventoryDtos.ReserveRequest;
import com.inventory.dto.InventoryDtos.ReserveResult;
import com.inventory.dto.InventoryDtos.StockView;
import com.inventory.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 库存接口（P0 契约，跨服务唯一入口；G1 下单链路 / G3 管理端均按此调用）。
 *
 * <ul>
 *   <li>{@code POST /api/v1/internal/inventory/preview} — 下单前校验（只读，不占库）</li>
 *   <li>{@code POST /api/v1/internal/inventory/reserve} — 下单预占（orderId + items + TTL）</li>
 *   <li>{@code POST /api/v1/internal/inventory/release} — 取消/超时关单释放（幂等）</li>
 *   <li>{@code POST /api/v1/internal/inventory/deduct}  — 确认收货出库（幂等）</li>
 *   <li>{@code GET  /api/v1/inventory/stocks?skuIds=}   — 库存查询</li>
 *   <li>{@code POST /api/v1/internal/inventory/stocks}  — P0 联调初始化库存记录</li>
 * </ul>
 *
 * <p>internal 路径仅供服务间调用（直连端口，不走网关），响应信封为 common 的 ApiResponse。</p>
 */
@RestController
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /** 校验库存是否充足（TOCTOU 说明：仅用于提示，真正的防超卖在 reserve 的事务内闭环）。 */
    @PostMapping("/api/v1/internal/inventory/preview")
    public ApiResponse<PreviewResult> preview(@Valid @RequestBody ItemsRequest request) {
        return ApiResponse.ok(inventoryService.preview(request.items()));
    }

    /** 预占库存：全部成功或全部失败（无部分预占）；重复 orderId 幂等。 */
    @PostMapping("/api/v1/internal/inventory/reserve")
    public ApiResponse<ReserveResult> reserve(@Valid @RequestBody ReserveRequest request) {
        Duration ttl = request.ttlSeconds() == null
                ? Duration.ofSeconds(InventoryService.DEFAULT_RESERVE_TTL_SECONDS)
                : Duration.ofSeconds(request.ttlSeconds());
        return ApiResponse.ok(inventoryService.reserve(request.orderId(), request.items(), ttl));
    }

    /** 释放预占（取消/超时关单）：幂等，重复调用不会重复回补。 */
    @PostMapping("/api/v1/internal/inventory/release")
    public ApiResponse<OperationResult> release(@Valid @RequestBody OrderIdRequest request) {
        return ApiResponse.ok(inventoryService.release(request.orderId()));
    }

    /** 扣减出库（确认收货）：幂等，重复调用不会重复扣。 */
    @PostMapping("/api/v1/internal/inventory/deduct")
    public ApiResponse<OperationResult> deduct(@Valid @RequestBody OrderIdRequest request) {
        return ApiResponse.ok(inventoryService.deduct(request.orderId()));
    }

    /** 批量查询库存（逗号分隔 skuIds）。 */
    @GetMapping("/api/v1/inventory/stocks")
    public ApiResponse<List<StockView>> stocks(@RequestParam("skuIds") String skuIds) {
        List<Long> ids = Arrays.stream(skuIds.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .collect(Collectors.toList());
        return ApiResponse.ok(inventoryService.stocks(ids));
    }

    /** P0 联调用：初始化 SKU 库存（管理端入库/出库/调整属 admin-inventory 域，此处仅建账本）。 */
    @PostMapping("/api/v1/internal/inventory/stocks")
    public ApiResponse<java.util.Map<String, Object>> initStock(@Valid @RequestBody InitStockRequest request) {
        Long id = inventoryService.initStock(request);
        return ApiResponse.ok(java.util.Map.of("id", id, "skuId", request.skuId(),
                "availableStock", request.availableStock()));
    }
}
