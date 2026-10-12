package com.product.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.PageResult;
import com.product.service.ProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 服务间内部契约（不对外暴露给客户端；P0 信任内网不做鉴权 —— DEV.md 4.5）。
 *
 * <p>调用方：core-order / core-cart（批量 SKU）、core-search（冗余同步）。
 * 响应仍走统一信封 {code,msg,data,traceId}，调用方用 RestClient 反序列化 ApiResponse 提取 data。</p>
 */
@RestController
public class InternalProductController {

    private final ProductService productService;

    public InternalProductController(ProductService productService) {
        this.productService = productService;
    }

    /** GET /api/v1/internal/skus?ids=1,2 —— 批量 SKU 视图：[{skuId,spuId,title,spec,images,status}] */
    @GetMapping("/api/v1/internal/skus")
    public ApiResponse<List<Map<String, Object>>> skus(@RequestParam String ids) {
        List<Long> parsed = new java.util.ArrayList<>();
        for (String s : ids.split(",")) {
            if (!s.isBlank()) {
                parsed.add(Long.parseLong(s.trim()));
            }
        }
        return ApiResponse.ok(productService.internalSkuViews(parsed));
    }

    /** GET /api/v1/internal/products?page=&pageSize= —— SPU 冗余同步视图（core-search 拉取）。 */
    @GetMapping("/api/v1/internal/products")
    public ApiResponse<PageResult<Map<String, Object>>> products(@RequestParam(defaultValue = "1") int page,
                                                                 @RequestParam(defaultValue = "200") int pageSize) {
        return ApiResponse.ok(productService.internalProducts(page, pageSize));
    }
}
