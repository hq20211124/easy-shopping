package com.cart.client;

import com.cart.client.dto.ClientDtos.StockView;

import java.util.List;

/**
 * core-inventory 客户端（契约见 core-inventory 模块 README）：{@code GET /api/v1/inventory/stocks?skuIds=}。
 *
 * <p>加购前不查库存（TOCTOU 禁止——预占在下单时由 core-order 调 reserve 强校验 3002），
 * 列表页仅取库存做展示（in_stock 标记）。</p>
 */
public interface InventoryClient {

    List<StockView> stocks(List<Long> skuIds);
}
