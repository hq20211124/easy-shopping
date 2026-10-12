package com.cart.client;

import com.cart.client.dto.ClientDtos.PriceView;

import java.util.List;

/**
 * core-price 客户端（契约见模块 README）：{@code GET /api/v1/price/skus?ids=}。
 *
 * <p>购物车只做展示级取价，结算/下单以 core-price 权威计价，客户端不可自证金额。</p>
 */
public interface PriceClient {

    List<PriceView> prices(List<Long> skuIds);
}
