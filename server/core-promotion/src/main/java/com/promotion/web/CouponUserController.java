package com.promotion.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
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
 * <p><b>鉴权（P0-F 修复）</b>：全部接口要求登录，网关注入 {@code X-User-Id}（{@code UserContext}）。
 * deduct/release <b>强制</b>携带身份并校验券主归属——原实现「不携带 X-User-Id 就按内网信任放行」
 * 等于让任意能直连 8088 的调用方核销/退回他人券（水平越权）。服务端间调用改走
 * {@link InternalCouponController}（{@code /api/v1/internal/coupons/{id}/deduct|release}，
 * 共享密钥 + 内网可达）。</p>
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
        return ApiResponse.ok(couponService.deduct(request.couponId(), request.orderId(), requireBuyerUserId()));
    }

    /** 关单退回（used → available；幂等）。 */
    @PostMapping({"/api/v1/coupons/release", "/api/v1/coupon/release"})
    public ApiResponse<CouponRecordResponse> release(@Valid @RequestBody ReleaseRequest request) {
        return ApiResponse.ok(couponService.release(request.couponId(), request.orderId(), requireBuyerUserId()));
    }

    /** 我的券列表（status 可选：0可用/1已使用/2已过期）。 */
    @GetMapping({"/api/v1/coupons/my", "/api/v1/coupon/my"})
    public ApiResponse<List<CouponRecordResponse>> my(
            @RequestParam(required = false) Integer status) {
        long userId = UserContext.requireUserId();
        return ApiResponse.ok(couponService.my(userId, status));
    }

    /**
     * 券写操作的身份闸门：无 {@code X-User-Id} 一律 {@link BizErrorCode#NO_PERMISSION}（1004/403）。
     *
     * <p>刻意不用 {@code requireUserId()}（1002/401）：本接口的缺失身份意味着「调用方根本没表明
     * 自己是哪个用户」，按权限问题归类比按登录态失效归类更准确，也避免客户端把 401 当成「Token 过期」
     * 去刷新重试（刷新后仍然没有 X-User-Id，只会陷入循环）。</p>
     */
    private long requireBuyerUserId() {
        Long userId = UserContext.userId();
        if (userId == null) {
            throw new BizException(BizErrorCode.NO_PERMISSION, "缺少用户身份（X-User-Id），无法操作优惠券");
        }
        return userId;
    }
}
