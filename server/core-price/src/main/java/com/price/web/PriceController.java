package com.price.web;

import com.easyshopping.common.api.ApiResponse;
import com.price.service.PriceService;
import com.price.service.PriceService.PriceView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * SKU 价格查询 —— 契约（任务 P0 约定，集成代理按此核对）：
 * GET /api/v1/price/skus?ids=1,2 → ApiResponse.data = [{skuId, price, marketPrice}]（BigDecimal）。
 */
@RestController
public class PriceController {

    private final PriceService priceService;

    public PriceController(PriceService priceService) {
        this.priceService = priceService;
    }

    @GetMapping("/api/v1/price/skus")
    public ApiResponse<List<PriceView>> listSkus(@RequestParam String ids) {
        List<Long> parsed = new ArrayList<>();
        for (String s : ids.split(",")) {
            if (!s.isBlank()) {
                parsed.add(Long.parseLong(s.trim()));
            }
        }
        return ApiResponse.ok(priceService.listBySkuIds(parsed));
    }
}
