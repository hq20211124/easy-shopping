package com.cart.client;

import com.cart.client.dto.ClientDtos.SkuView;

import java.util.List;

/**
 * core-product 客户端（契约见模块 README）：{@code GET /api/v1/internal/skus?ids=}。
 *
 * <p>返回存在的 SKU 行；不存在的 ID 不在响应内（调用方按 3001 缺失 / 3003 下架处理）。</p>
 */
public interface ProductClient {

    List<SkuView> skus(List<Long> skuIds);
}
