package com.order.client;

import com.order.client.dto.ClientDtos.InventoryItem;

import java.util.List;

/**
 * core-inventory 客户端（契约见 core-inventory 模块 README）。
 *
 * <ul>
 *   <li>{@code POST /api/v1/internal/inventory/preview} — 下单前校验（只读）</li>
 *   <li>{@code POST /api/v1/internal/inventory/reserve} — 预占（orderId + items + TTL）</li>
 *   <li>{@code POST /api/v1/internal/inventory/release} — 释放（取消/超时关单，幂等）</li>
 *   <li>{@code POST /api/v1/internal/inventory/deduct} — 出库（确认收货，幂等）</li>
 * </ul>
 */
public interface InventoryClient {

    /** 预占（全部成功或全部失败；库存不足抛 3002）。 */
    void reserve(Long orderId, List<InventoryItem> items, long ttlSeconds);

    /** 释放预占（幂等）。 */
    void release(Long orderId);

    /** 确认收货出库（幂等）。 */
    void deduct(Long orderId);
}
