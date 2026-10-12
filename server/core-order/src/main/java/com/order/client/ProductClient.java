package com.order.client;

import com.order.client.dto.ClientDtos.SkuView;

import java.util.List;

/**
 * core-product 客户端：批量拉取 SKU 信息（名称/图片/店铺/上下架）。
 *
 * <p>契约：{@code GET {core-product}/api/v1/internal/skus?ids=1,2} →
 * {@code data: [{skuId, spuId, storeId, name, image, onShelf}]}。</p>
 */
public interface ProductClient {

    /** 批量查询 SKU 视图（只返回存在的 SKU，缺失项由调用方判定 3001）。 */
    List<SkuView> skus(List<Long> skuIds);
}
