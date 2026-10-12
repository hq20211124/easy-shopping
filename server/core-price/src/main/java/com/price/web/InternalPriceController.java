package com.price.web;

import com.easyshopping.common.api.ApiResponse;
import com.price.service.PriceService;
import com.price.web.PriceDtos.InternalPriceBatchInput;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部写价契约（调用方 = core-product；P0 信任内网不鉴权 —— DEV.md 4.5）。
 * POST /api/v1/internal/price/skus body={items:[{skuId,price,marketPrice}]}。
 */
@RestController
public class InternalPriceController {

    private final PriceService priceService;

    public InternalPriceController(PriceService priceService) {
        this.priceService = priceService;
    }

    @PostMapping("/api/v1/internal/price/skus")
    public ApiResponse<Void> upsertBatch(@Valid @RequestBody InternalPriceBatchInput in) {
        priceService.upsertBatch(in.items());
        return ApiResponse.ok();
    }
}
