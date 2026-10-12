package com.order.web;

import com.easyshopping.common.api.ApiResponse;
import com.order.domain.OrderMain;
import com.order.dto.OrderDtos.PaidRequest;
import com.order.dto.OrderDtos.PaidResponse;
import com.order.service.OrderLifecycleService;
import com.order.service.OrderQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 内部接口（core-payment 直连 :8086 调用，不走网关）。
 *
 * <ul>
 *   <li>{@code POST /api/v1/internal/orders/{orderId}/paid} — 支付成功回调：
 *       幂等（重复回调返回 ALREADY_PAID）；与超时关单并发时 version CAS 裁决，
 *       已关单收到回调 → 自动原路退款（outcome=REFUND_TRIGGERED，android-backend 4.2.4）。</li>
 *   <li>{@code GET  /api/v1/internal/orders/{orderId} — 订单原始快照（prepay 取额 / 对账）。
 *       服务间调用无 X-User-Id，不能走买家 {@code /api/v1/orders/{id}}（会被 1002 拦下）。</li>
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

    /** 订单快照：字段名对齐 core-payment 的 {@code RestOrderClient#toSnapshot} 解析口径。 */
    @GetMapping("/api/v1/internal/orders/{orderId}")
    public ApiResponse<InternalOrderSnapshot> snapshot(@PathVariable("orderId") Long orderId) {
        OrderMain order = queryService.internalOrder(orderId);
        return ApiResponse.ok(new InternalOrderSnapshot(order.getId(), order.getUserId(),
                order.getTotalAmount(), order.getOrderStatus(), order.getPayDeadline()));
    }

    /**
     * 服务间订单快照（core-payment {@code OrderSnapshot} 的来源）。
     *
     * @param orderId     订单 ID（core-payment 侧兼容 orderId/id 两个字段名，这里给 orderId）
     * @param userId      下单用户（prepay 做归属校验）
     * @param totalAmount 应付金额（prepay 定额，客户端金额一律忽略 — 裁决 C7）
     * @param status      0 基 8 值订单状态（AGENTS.md 唯一事实来源）
     * @param payDeadline 支付截止（订单表为唯一事实来源）
     */
    public record InternalOrderSnapshot(Long orderId, Long userId, BigDecimal totalAmount,
                                        Integer status, Instant payDeadline) {
    }
}
