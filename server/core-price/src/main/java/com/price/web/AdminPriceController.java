package com.price.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.UserContext;
import com.easyshopping.common.idempotent.Idempotent;
import com.price.domain.SkuPrice;
import com.price.service.PriceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端改价 —— 路径对齐 plans/admin-backend.md 3.2.1 批量改价语义的单条版本
 * （P0 简化：单条同步；批量改价按 3.3.3 走审批流 + 异步任务，P1 由 admin-bff 承载）。
 * 鉴权：网关注入角色头，本服务判 ROLE_ADMIN（DEV.md 4.5）。
 */
@RestController
public class AdminPriceController {

    private final PriceService priceService;

    public AdminPriceController(PriceService priceService) {
        this.priceService = priceService;
    }

    /** PUT /api/admin/price/skus/{skuId} —— 创建/修改 SKU 价格（价格变更事件随事务提交后发布）。 */
    @PutMapping("/api/admin/price/skus/{skuId}")
    @Idempotent
    public ApiResponse<PriceViewResponse> upsert(@PathVariable Long skuId,
                                                 @Valid @RequestBody PriceDtos.PriceUpsertInput in) {
        // 契约对齐：网关注入 core-user 的 ADMIN，兼容历史 ROLE_ADMIN 写法（UserContext.isAdmin）
        if (!UserContext.isAdmin()) {
            throw new BizException(BizErrorCode.NO_PERMISSION);
        }
        SkuPrice saved = priceService.upsert(skuId, in.price(), in.marketPrice());
        return ApiResponse.ok(new PriceViewResponse(saved.getSkuId(), saved.getPrice(), saved.getMarketPrice()));
    }

    /** 响应视图（与 GET /api/v1/price/skus 元素同构）。 */
    public record PriceViewResponse(Long skuId, java.math.BigDecimal price, java.math.BigDecimal marketPrice) {
    }
}
