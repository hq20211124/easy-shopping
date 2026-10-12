package com.promotion.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.promotion.service.CouponService;
import com.promotion.web.dto.CouponRecordResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * <b>服务间</b>券核销/退券端点（core-order 下单 / 关单 / 支付失败补偿链路，直连 :8088，不走网关）。
 *
 * <p><b>为什么要与买家端分开（P0-F 修复）</b>：原实现把 deduct/release 同时注册在买家端
 * {@code /api/v1/coupons/*} 下，且「无 X-User-Id 即跳过归属校验」——只要能直连 8088 就能
 * 核销/退回任意用户的券（水平越权）。现在：</p>
 * <ul>
 *   <li>买家端 {@code /api/v1/coupons/*}：<b>强制</b>携带 {@code X-User-Id} 并校验券主归属，
 *       缺失身份 → 1004/403（见 {@code CouponUserController}）；</li>
 *   <li>本类 {@code /api/v1/internal/coupons/*}：仅 core-order 调用，鉴权改为
 *       <b>共享密钥请求头 {@code X-Internal-Token}</b>（常量时间比较），
 *       网关无该路径路由（{@code gateway/application.yml} 未配置 {@code /api/v1/internal/**}）。</li>
 * </ul>
 *
 * <p><b>密钥配置</b>：{@code app.internal.token}，默认留空 = <b>拒绝所有 internal 调用</b>（fail-closed，
 * 避免像 JWT 那样把开发密钥硬编码进仓库）。生产通过环境变量 {@code APP_INTERNAL_TOKEN} 注入，
 * 与调用方 core-order 的 {@code service.internal.token} 保持一致。P1 换 mTLS 后删除本机制。</p>
 */
@RestController
@RequestMapping("/api/v1/internal/coupons")
public class InternalCouponController {

    /** 服务间共享密钥请求头。 */
    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    private final CouponService couponService;
    private final String internalToken;

    public InternalCouponController(CouponService couponService,
                                    @Value("${app.internal.token:}") String internalToken) {
        this.couponService = couponService;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    /** 核销：{@code POST /api/v1/internal/coupons/{id}/deduct} body={orderId}。 */
    @PostMapping("/{id}/deduct")
    public ApiResponse<CouponRecordResponse> deduct(@PathVariable("id") Long recordId,
                                                    @RequestBody(required = false) InternalOrderRef request,
                                                    HttpServletRequest http) {
        requireInternalToken(http);
        Long orderId = request == null ? null : request.orderId();
        if (orderId == null) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "orderId 不能为空");
        }
        return ApiResponse.ok(couponService.deductInternal(recordId, orderId));
    }

    /** 退券：{@code POST /api/v1/internal/coupons/{id}/release} body={orderId?}（orderId 传入时校验绑定一致）。 */
    @PostMapping("/{id}/release")
    public ApiResponse<CouponRecordResponse> release(@PathVariable("id") Long recordId,
                                                     @RequestBody(required = false) InternalOrderRef request,
                                                     HttpServletRequest http) {
        requireInternalToken(http);
        return ApiResponse.ok(couponService.releaseInternal(recordId, request == null ? null : request.orderId()));
    }

    /**
     * 共享密钥校验：密钥未配置 → 直接拒绝（fail-closed）；请求头缺失或不等 → 1004/403。
     * 用 {@link MessageDigest#isEqual} 常量时间比较防时序侧信道。
     */
    private void requireInternalToken(HttpServletRequest request) {
        if (internalToken.isEmpty()) {
            throw new BizException(BizErrorCode.NO_PERMISSION,
                    "内部调用未启用：请配置 app.internal.token / APP_INTERNAL_TOKEN");
        }
        String provided = request.getHeader(HEADER_INTERNAL_TOKEN);
        if (provided == null || provided.isBlank()
                || !MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8),
                        provided.trim().getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(BizErrorCode.NO_PERMISSION, "服务间调用鉴权失败");
        }
    }

    /** internal 请求体：只带订单引用。 */
    public record InternalOrderRef(Long orderId) {
    }
}
