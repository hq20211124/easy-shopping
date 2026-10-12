package com.payment.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.UserContext;
import com.payment.service.NotifyCommand;
import com.payment.service.NotifyOutcome;
import com.payment.service.PaymentNotifyService;
import com.payment.service.PaymentService;
import com.payment.web.dto.MockPayRequest;
import com.payment.web.dto.MockPayResponse;
import com.payment.web.dto.NotifyRequest;
import com.payment.web.dto.NotifyResponse;
import com.payment.web.dto.PaymentStatusResponse;
import com.payment.web.dto.PrepayRequest;
import com.payment.web.dto.PrepayResponse;
import com.payment.web.dto.RefundRequest;
import com.payment.web.dto.RefundResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付接口（:8087）。
 *
 * <p>契约要点：prepay 只收 orderId（金额服务端定额，裁决 C7 防改价）；
 * notify 为渠道回调（回调幂等三步见 {@link PaymentNotifyService}）；
 * mock/pay 为 P0 模拟付款（走与真实回调相同的处理链）；
 * refund 供订单关单/售后服务调用（服务端间，P0 信任内网）。</p>
 *
 * <p>幂等口径：本模块写接口不使用 common 的 {@code @Idempotent}（进程内 Map，单实例有效），
 * 一律以 DB 唯一键/状态 CAS 实现多实例安全的天然幂等——prepay 按 orderId 复用支付单、
 * notify 按 notify_id 去重、refund 按状态 CAS。详见 README。</p>
 */
@RestController
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentNotifyService notifyService;

    public PaymentController(PaymentService paymentService, PaymentNotifyService notifyService) {
        this.paymentService = paymentService;
        this.notifyService = notifyService;
    }

    /** 预支付（需登录）：只收 orderId + channel，金额/用户服务端反查订单定额（C7）。 */
    @PostMapping("/api/v1/payment/prepay")
    public ApiResponse<PrepayResponse> prepay(@Valid @RequestBody PrepayRequest request) {
        long userId = UserContext.requireUserId();
        return ApiResponse.ok(paymentService.prepay(userId, request.orderId(), request.channel()));
    }

    /** P0 mock 支付：模拟用户付款成功（需登录），直接进回调处理链。 */
    @PostMapping("/api/v1/payment/mock/pay")
    public ApiResponse<MockPayResponse> mockPay(@Valid @RequestBody MockPayRequest request) {
        long userId = UserContext.requireUserId();
        PaymentService.MockPayResult result = paymentService.mockPay(request.paymentId(), userId);
        return ApiResponse.ok(new MockPayResponse(result.paymentId(), result.status()));
    }

    /**
     * 支付回调（渠道 → 平台）：幂等三步 notify_log 唯一键 → 状态 CAS → 金额校验。
     * <p>TODO(gateway)：真实渠道回调需在网关白名单放行（当前 /api/v1/payment/** 要求 Token），
     * P0 mock 调试直连 :8087 或带 Token。</p>
     */
    @PostMapping("/api/v1/payment/notify")
    public ApiResponse<NotifyResponse> notify(@Valid @RequestBody NotifyRequest request) {
        String notifyId = (request.notifyId() == null || request.notifyId().isBlank())
                ? request.thirdPartyNo() : request.notifyId();
        NotifyOutcome outcome = notifyService.handleNotify(
                new NotifyCommand(notifyId, request.paymentId(), request.amount(), request.thirdPartyNo()));
        return ApiResponse.ok(new NotifyResponse(notifyId, request.paymentId(),
                outcome.paid() ? NotifyResponse.RESULT_SUCCESS : NotifyResponse.RESULT_DUPLICATE));
    }

    /** 查单轮询（需登录）：按订单查支付状态。 */
    @GetMapping("/api/v1/payment/status/{orderId}")
    public ApiResponse<PaymentStatusResponse> status(@PathVariable Long orderId) {
        long userId = UserContext.requireUserId();
        return ApiResponse.ok(paymentService.status(orderId, userId));
    }

    /** 退款（供订单关单/售后调用，服务端间；幂等：paid → refunding → refunded）。 */
    @PostMapping("/api/v1/payment/refund")
    public ApiResponse<RefundResponse> refund(@Valid @RequestBody RefundRequest request) {
        return ApiResponse.ok(paymentService.refund(request.orderId(), request.reason()));
    }
}
