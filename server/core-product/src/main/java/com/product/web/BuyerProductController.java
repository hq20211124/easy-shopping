package com.product.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.PageResult;
import com.product.service.ProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 买家端商品接口。
 *
 * <p>契约来源：GET /api/v1/products（列表参数 plans/ios-backend.md 3.2.1，卡片字段 plans/android-backend.md 3.1.3）；
 * GET /api/v1/products/category/{categoryId}（plans/android-backend.md 3.1.2）；
 * GET /api/v1/products/{spuId}（详情字段 plans/android-backend.md 3.2.3）。
 * 字段名统一 camelCase（骨架响应信封口径，ios 侧 snake_case 由前端/BFF 映射 —— DEV.md 3）。</p>
 */
@RestController
public class BuyerProductController {

    private final ProductService productService;

    public BuyerProductController(ProductService productService) {
        this.productService = productService;
    }

    /** GET /api/v1/products —— 商品列表（类目/关键词/价格区间/排序筛选，pageNum 从 1 起，pageSize≤200）。 */
    @GetMapping("/api/v1/products")
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sort, // default/price_asc/price_desc/sales/newest
            @RequestParam(required = false) BigDecimal priceMin,
            @RequestParam(required = false) BigDecimal priceMax,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(productService.buyerList(categoryId, keyword, sort, priceMin, priceMax, page, pageSize));
    }

    /** GET /api/v1/products/category/{categoryId} —— 按类目取商品（android 3.1.2，同列表契约）。 */
    @GetMapping("/api/v1/products/category/{categoryId}")
    public ApiResponse<PageResult<Map<String, Object>>> listByCategory(
            @PathVariable Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) BigDecimal priceMin,
            @RequestParam(required = false) BigDecimal priceMax,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(productService.buyerList(categoryId, keyword, sort, priceMin, priceMax, page, pageSize));
    }

    /** GET /api/v1/products/{spuId} —— 商品详情（含 SKU 列表与规格聚合；不存在 3001 / 未上架 3003）。 */
    @GetMapping("/api/v1/products/{spuId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long spuId) {
        return ApiResponse.ok(productService.buyerDetail(spuId));
    }
}
