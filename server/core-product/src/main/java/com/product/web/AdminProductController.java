package com.product.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.api.UserContext;
import com.easyshopping.common.idempotent.Idempotent;
import com.product.domain.Spu;
import com.product.service.ProductService;
import com.product.service.ProductService.ProductDetail;
import com.product.web.ProductDtos.AuditRejectInput;
import com.product.web.ProductDtos.AuditReturnInput;
import com.product.web.ProductDtos.BatchShelfInput;
import com.product.web.ProductDtos.ProductInput;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端商品接口 —— 路径对齐 plans/admin-backend.md 3.2.1 商品接口表；
 * 审核动作子路径（submit-audit/approve/reject/return/on-shelf/off-shelf）按 3.4 审核中心拆分落在本服务
 * （P0 简化：审核任务队列归 admin-audit 服务 P1，本服务只做状态机落库 + 事件）。
 */
@RestController
public class AdminProductController {

    private final ProductService productService;

    public AdminProductController(ProductService productService) {
        this.productService = productService;
    }

    /** GET /api/admin/product/list —— 分页查询商品 */
    @GetMapping("/api/admin/product/list")
    public ApiResponse<PageResult<Spu>> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Long categoryId,
                                             @RequestParam(required = false) Integer auditStatus,
                                             @RequestParam(required = false) Integer shelfStatus,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int pageSize) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(productService.adminPage(keyword, categoryId, auditStatus, shelfStatus, page, pageSize));
    }

    /** GET /api/admin/product/{id} —— 商品详情（costPrice 按 cost:read 字段级权限脱敏，plans/admin-backend.md 2.2.3）。 */
    @GetMapping("/api/admin/product/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        ProductDtos.requireAdmin();
        ProductDetail detail = productService.adminDetail(id);
        Map<String, Object> body = detail.toAdminMap();
        if (!UserContext.roles().contains("cost:read")) {
            maskCostPrice(body);
        }
        return ApiResponse.ok(body);
    }

    /** POST /api/admin/product —— 创建商品（P0：创建即草稿） */
    @PostMapping("/api/admin/product")
    @Idempotent
    public ApiResponse<Spu> create(@RequestBody ProductInput in) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(productService.create(in));
    }

    /** PUT /api/admin/product/{id} —— 更新商品（仅 draft/rejected） */
    @PutMapping("/api/admin/product/{id}")
    @Idempotent
    public ApiResponse<Spu> update(@PathVariable Long id, @RequestBody ProductInput in) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(productService.update(id, in));
    }

    /** DELETE /api/admin/product/{id} —— 软删除（进回收站） */
    @DeleteMapping("/api/admin/product/{id}")
    @Idempotent
    public ApiResponse<Void> delete(@PathVariable Long id) {
        ProductDtos.requireAdmin();
        productService.softDelete(id);
        return ApiResponse.ok();
    }

    /** POST /api/admin/product/{id}/restore —— 回收站恢复 */
    @PostMapping("/api/admin/product/{id}/restore")
    @Idempotent
    public ApiResponse<Spu> restore(@PathVariable Long id) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(productService.restore(id));
    }

    /** DELETE /api/admin/product/{id}/permanent —— 彻底删除（回收站中执行） */
    @DeleteMapping("/api/admin/product/{id}/permanent")
    @Idempotent
    public ApiResponse<Void> permanentDelete(@PathVariable Long id) {
        ProductDtos.requireAdmin();
        productService.permanentDelete(id);
        return ApiResponse.ok();
    }

    /** POST /api/admin/product/{id}/submit-audit —— 提交审核：draft/rejected → auditing */
    @PostMapping("/api/admin/product/{id}/submit-audit")
    @Idempotent
    public ApiResponse<Spu> submitAudit(@PathVariable Long id) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(productService.submitAudit(id));
    }

    /** POST /api/admin/product/{id}/approve —— 审核通过：auditing → on_shelf + 自动上架 */
    @PostMapping("/api/admin/product/{id}/approve")
    @Idempotent
    public ApiResponse<Spu> approve(@PathVariable Long id) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(productService.approve(id));
    }

    /** POST /api/admin/product/{id}/reject —— 审核驳回：auditing → rejected（reason 必填） */
    @PostMapping("/api/admin/product/{id}/reject")
    @Idempotent
    public ApiResponse<Spu> reject(@PathVariable Long id, @RequestBody AuditRejectInput in) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(productService.reject(id, in.reason()));
    }

    /** POST /api/admin/product/{id}/return —— 退回修改：auditing → draft（reason 必填） */
    @PostMapping("/api/admin/product/{id}/return")
    @Idempotent
    public ApiResponse<Spu> returnToDraft(@PathVariable Long id, @RequestBody AuditReturnInput in) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(productService.returnToDraft(id, in.reason()));
    }

    /** POST /api/admin/product/{id}/on-shelf —— 上架（前置：审核通过） */
    @PostMapping("/api/admin/product/{id}/on-shelf")
    @Idempotent
    public ApiResponse<Spu> onShelf(@PathVariable Long id) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(productService.onShelf(id));
    }

    /** POST /api/admin/product/{id}/off-shelf —— 下架（不回退审核状态） */
    @PostMapping("/api/admin/product/{id}/off-shelf")
    @Idempotent
    public ApiResponse<Spu> offShelf(@PathVariable Long id) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(productService.offShelf(id));
    }

    /** POST /api/admin/product/batch-on-shelf —— 批量上架（P0 同步，≤500） */
    @PostMapping("/api/admin/product/batch-on-shelf")
    @Idempotent
    public ApiResponse<Map<String, Object>> batchOnShelf(@RequestBody BatchShelfInput in) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(productService.batchShelf(in.ids(), true));
    }

    /** POST /api/admin/product/batch-off-shelf —— 批量下架（P0 同步，≤500） */
    @PostMapping("/api/admin/product/batch-off-shelf")
    @Idempotent
    public ApiResponse<Map<String, Object>> batchOffShelf(@RequestBody BatchShelfInput in) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(productService.batchShelf(in.ids(), false));
    }

    /** cost:read 字段级权限脱敏：无权限角色看到的 costPrice 恒为 null（后端脱敏为主）。 */
    private static void maskCostPrice(Map<String, Object> adminMap) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skus = (List<Map<String, Object>>) adminMap.get("skus");
        if (skus == null) {
            return;
        }
        for (Map<String, Object> sku : skus) {
            sku.put("costPrice", null);
        }
    }
}
