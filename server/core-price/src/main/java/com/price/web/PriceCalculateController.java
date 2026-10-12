package com.price.web;

import com.easyshopping.common.api.ApiResponse;
import com.price.service.PriceService;
import com.price.service.PriceService.CalculateResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 下单试算 —— 契约（任务 P0 约定）：
 * POST /api/v1/price/calculate {items:[{skuId,quantity}], couponId?}
 * → {items:[{skuId,quantity,unitPrice,marketPrice,subtotal}], totalQuantity, totalAmount, discountAmount, payableAmount, coupon}。
 * 优惠券核销在 core-promotion，本服务仅 preview（P0 未就绪本地降级，见 CouponPreviewClient）。
 */
@RestController
public class PriceCalculateController {

    private final PriceService priceService;

    public PriceCalculateController(PriceService priceService) {
        this.priceService = priceService;
    }

    @PostMapping("/api/v1/price/calculate")
    public ApiResponse<CalculateResponse> calculate(@Valid @RequestBody PriceDtos.CalculateInput in) {
        return ApiResponse.ok(priceService.calculate(in));
    }
}
