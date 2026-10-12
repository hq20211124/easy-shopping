package com.order.web;

import com.easyshopping.common.api.ApiResponse;
import com.order.dto.OrderDtos.InternalOrderSnapshot;
import com.order.dto.OrderDtos.PaidRequest;
import com.order.dto.OrderDtos.PaidResponse;
import com.order.service.OrderLifecycleService;
import com.order.service.OrderQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口（core-payment 直连 :8086 调用，不走网关）。
 *
 * <ul>
 *   <li>{@code POST /api/v1/internal/orders/{orderId}/paid} — 支付成功回调：
 *       幂等（重复回调返回 ALREADY_PAID）；与超时关单并发时 version CAS 裁决，
 *       已关单收到回调 → 自动原路退款（outcome=REFUND_TRIGGERED，android-backend 4.2.4）。</li>
 *   <li>{@code GET  /api/v1/internal/orders/{orderId} — 订单字段快照（prepay 取额 / 对账）。
 *       <b>金额字段口径：{@code payAmount}（应付）才是支付域定额依据</b>，{@code totalAmount} 仅供对账参考。
 *       服务间调用无 X-User-Id，不能走买家 {@code /api/v1/orders/{id}}（会被 1002 拦下）。
 *       <b>只返回 {@link InternalOrderSnapshot} 显式字段，不返回订单实体</b>：
 *       实体整返会连同明文 {@code receiverMobile} / 收货地址一起开放给服务间调用方
 *       （它们一个都不消费），属审计 P1 遗留项。</li>
 * </ul>
 */
@RestController
public class InternalOrderController {

    private final OrderLifecycleService lifecycleService;
    private final OrderQueryService queryService;

    public InternalOrderController(OrderLifecycleService lifecycleService, OrderQueryService queryService) {
        this.lifecycleService = lifecycleService;
        this.queryService = queryService;
    }

    @PostMapping("/api/v1/internal/orders/{orderId}/paid")
    public ApiResponse<PaidResponse> paid(@PathVariable("orderId") Long orderId,
                                          @RequestBody(required = false) PaidRequest request) {
        return ApiResponse.ok(lifecycleService.markPaid(orderId, request == null ? null : request.payType()));
    }

    /**
     * 订单字段快照。
     *
     * <p>字段名与消费口径对齐 core-payment {@code RestOrderClient#toSnapshot}，
     * <b>字段集不得增删</b>（改名/缺失会让 prepay fail-closed 报 9999）；字段与消费方的逐一对照见
     * {@link InternalOrderSnapshot} 类注释。</p>
     */
    @GetMapping("/api/v1/internal/orders/{orderId}")
    public ApiResponse<InternalOrderSnapshot> snapshot(@PathVariable("orderId") Long orderId) {
        return ApiResponse.ok(queryService.internalOrder(orderId));
    }
}