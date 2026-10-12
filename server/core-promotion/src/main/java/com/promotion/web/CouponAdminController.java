package com.promotion.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.api.UserContext;
import com.promotion.service.CouponService;
import com.promotion.web.dto.CouponRecordResponse;
import com.promotion.web.dto.CouponTemplateRequest;
import com.promotion.web.dto.CouponTemplateResponse;
import com.promotion.web.dto.DistributeRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端券模板接口（路径对齐 plans/admin-backend.md 7.2.1；
 * 创建另注册 {@code POST /api/admin/coupons} 别名兼容任务契约的复数写法）。
 *
 * <p>鉴权：要求角色 {@code admin}（网关注入 X-User-Roles；admin-bff 直连调用时须透传该头，
 * 缺失抛 1004，见 DEV.md 4.5）。</p>
 *
 * <p>注：网关把 {@code /api/admin/**} 路由到 admin-bff；本组接口供 admin-bff（admin-marketing）
 * 以服务间直调方式使用（地址 {@code service.urls.core-promotion}），P0 不经网关。</p>
 */
@RestController
public class CouponAdminController {

    private final CouponService couponService;

    public CouponAdminController(CouponService couponService) {
        this.couponService = couponService;
    }

    /** 创建券模板（{@code POST /api/admin/coupons} 为任务契约别名）。 */
    @PostMapping({"/api/admin/coupon", "/api/admin/coupons"})
    public ApiResponse<CouponTemplateResponse> create(@Valid @RequestBody CouponTemplateRequest request) {
        requireAdmin();
        return ApiResponse.ok(couponService.createTemplate(request));
    }

    /** 更新券模板（整体覆盖；已发放时禁止把 totalCount 调到已领数以下）。 */
    @PutMapping("/api/admin/coupon/{id}")
    public ApiResponse<CouponTemplateResponse> update(@PathVariable Long id,
                                                      @Valid @RequestBody CouponTemplateRequest request) {
        requireAdmin();
        return ApiResponse.ok(couponService.updateTemplate(id, request));
    }

    /** 删除券模板（已发放的只能下线，不可删除）。 */
    @DeleteMapping("/api/admin/coupon/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        requireAdmin();
        couponService.deleteTemplate(id);
        return ApiResponse.ok();
    }

    @GetMapping("/api/admin/coupon/{id}")
    public ApiResponse<CouponTemplateResponse> get(@PathVariable Long id) {
        requireAdmin();
        return ApiResponse.ok(couponService.getTemplate(id));
    }

    /** 模板分页列表。 */
    @GetMapping("/api/admin/coupon/list")
    public ApiResponse<PageResult<CouponTemplateResponse>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        requireAdmin();
        List<CouponTemplateResponse> list = couponService.listTemplates(pageNum, pageSize);
        return ApiResponse.ok(PageResult.of(list, couponService.countTemplates(), pageNum, pageSize));
    }

    /** 领取记录（审计）。 */
    @GetMapping("/api/admin/coupon/{id}/records")
    public ApiResponse<List<CouponRecordResponse>> records(@PathVariable Long id) {
        requireAdmin();
        return ApiResponse.ok(couponService.templateRecords(id));
    }

    /** 指定用户发放。 */
    @PostMapping("/api/admin/coupon/{id}/distribute")
    public ApiResponse<CouponRecordResponse> distribute(@PathVariable Long id,
                                                        @Valid @RequestBody DistributeRequest request) {
        requireAdmin();
        return ApiResponse.ok(couponService.distribute(id, request.userId()));
    }

    private void requireAdmin() {
        // 契约对齐：网关注入 core-user/admin-bff 的 ADMIN（原只认小写 admin，实际头为大写）
        if (!UserContext.isAdmin()) {
            throw new BizException(BizErrorCode.NO_PERMISSION);
        }
    }
}
