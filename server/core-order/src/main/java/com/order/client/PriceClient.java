package com.order.client;

import com.order.client.dto.ClientDtos.PriceView;

import java.util.List;

/**
 * core-price 客户端：批量获取 SKU 权威价格（下单金额唯一来源，客户端金额一律忽略）。
 *
 * <p>契约：{@code GET {core-price}/api/v1/price/skus?ids=1,2} → {@code data: [{skuId, price}]}。</p>
 */
public interface PriceClient {

    List<PriceView> prices(List<Long> skuIds);
}
