package com.promotion.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.UserContext;
import com.promotion.service.CouponService;
import com.promotion.web.dto.CouponRecordResponse;
import com.promotion.web.dto.DeductRequest;
import com.promotion.web.dto.PreviewRequest;
import com.promotion.web.dto.PreviewResponse;
import com.promotion.web.dto.ReceiveRequest;
import com.promotion.web.dto.ReleaseRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 买家侧优惠券接口（:8088）。
 *
 * <p><b>路径口径</b>：主路径 {@code /api/v1/coupons/*}（任务契约 + plans/pc-client-backend.md 1.2.1）；
 * 同时注册 {@code /api/v1/coupon/*} 别名——① 网关静态路由只配了 {@code Path=/api/v1/coupon/**}，
 * ② plans/android-backend.md P1 清单用单数路径。两路径完全等价，网关路由表补齐后可收敛为一个
 * （收敛属 gateway 模块，由其负责人处理）。</p>
 *
 * <p>鉴权：买家接口需登录（网关注入 X-User-Id）；deduct/release 允许服务端间调用（core-order
 * 下单/关单链路）——携带 X-User-Id 时校验归属，不携带时按内网信任放行（P0，对齐 DEV.md 4.5）。</p>
 */
@RestController
public class CouponUserController {

    private final CouponService couponService;

    public CouponUserController(CouponService couponService) {
        this.couponService = couponService;
    }

    /** 领券（防超领：总量条件更新 + 每人限领）。 */
    @PostMapping({"/api/v1/coupons/receive", "/api/v1/coupon/receive"})
    public ApiResponse<CouponRecordResponse> receive(@Valid @RequestBody ReceiveRequest request) {
        long userId = UserContext.requireUserId();
        return ApiResponse.ok(couponService.receive(request.couponId(), userId));
    }

    /** 领券（android P1 契约路径别名：POST /api/v1/coupon/{id}/receive）。 */
    @PostMapping("/api/v1/coupon/{id}/receive")
    public ApiResponse<CouponRecordResponse> receiveByPath(@PathVariable Long id) {
        long userId = UserContext.requireUserId();
        return ApiResponse.ok(couponService.receive(id, userId));
    }

    /** 优惠试算：couponId=用户券记录 ID，amount=订单金额列表（服务端求和定优惠）。 */
    @PostMapping({"/api/v1/coupons/preview", "/api/v1/coupon/preview"})
    public ApiResponse<PreviewResponse> preview(@Valid @RequestBody PreviewRequest request) {
        long userId = UserContext.requireUserId();
        return ApiResponse.ok(couponService.preview(request.couponId(), request.amount(), userId));
    }

    /** 核销（available → used + 绑定订单；条件更新防并发重复核销；同订单重试幂等）。 */
    @PostMapping({"/api/v1/coupons/deduct", "/api/v1/coupon/deduct"})
    public ApiResponse<CouponRecordResponse> deduct(@Valid @RequestBody DeductRequest request) {
        return ApiResponse.ok(couponService.deduct(request.couponId(), request.orderId(), UserContext.userId()));
    }

    /** 关单退回（used → available；幂等）。 */
    @PostMapping({"/api/v1/coupons/release", "/api/v1/coupon/release"})
    public ApiResponse<CouponRecordResponse> release(@Valid @RequestBody ReleaseRequest request) {
        return ApiResponse.ok(couponService.release(request.couponId(), request.orderId(), UserContext.userId()));
    }

    /** 我的券列表（status 可选：0可用/1已使用/2已过期）。 */
    @GetMapping({"/api/v1/coupons/my", "/api/v1/coupon/my"})
    public ApiResponse<List<CouponRecordResponse>> my(
            @RequestParam(required = false) Integer status) {
        long userId = UserContext.requireUserId();
        return ApiResponse.ok(couponService.my(userId, status));
    }
}
