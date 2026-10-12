package com.order.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.api.UserContext;
import com.easyshopping.common.idempotent.Idempotent;
import com.order.domain.OrderStatus;
import com.order.dto.OrderDtos.CancelRequest;
import com.order.dto.OrderDtos.CreateOrderRequest;
import com.order.dto.OrderDtos.CreateOrderResponse;
import com.order.dto.OrderDtos.OrderDetailVO;
import com.order.dto.OrderDtos.OrderListVO;
import com.order.service.OrderCreateService;
import com.order.service.OrderLifecycleService;
import com.order.service.OrderQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 买家端订单接口（契约：plans/ios-backend.md 4.2.2/4.2.3/4.2.5 + plans/android-backend.md 4.3.2）。
 *
 * <ul>
 *   <li>{@code POST /api/v1/orders} — 下单（@Idempotent，客户端必带 Idempotency-Key；别名 /api/v1/order/create）</li>
 *   <li>{@code GET  /api/v1/orders} — 列表（分页 + 状态 Tab：0~7 或枚举名/all）</li>
 *   <li>{@code GET  /api/v1/orders/{orderId}} — 详情（含子单/金额明细/timeline）</li>
 *   <li>{@code POST /api/v1/orders/{orderId}/cancel} — 取消（仅待付款）</li>
 *   <li>{@code POST /api/v1/orders/{orderId}/confirm} — 确认收货（出库扣库存）</li>
 * </ul>
 */
@RestController
public class OrderController {

    private final OrderCreateService createService;
    private final OrderQueryService queryService;
    private final OrderLifecycleService lifecycleService;

    public OrderController(OrderCreateService createService,
                           OrderQueryService queryService,
                           OrderLifecycleService lifecycleService) {
        this.createService = createService;
        this.queryService = queryService;
        this.lifecycleService = lifecycleService;
    }

    /** 创建订单（写接口必带 Idempotency-Key，重复提交返回 1005 冲突、不会重复建单）。 */
    @Idempotent
    @PostMapping({"/api/v1/orders", "/api/v1/order/create"})
    public ApiResponse<CreateOrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        long userId = UserContext.requireUserId();
        return ApiResponse.ok(createService.create(userId, request));
    }

    @GetMapping("/api/v1/orders")
    public ApiResponse<PageResult<OrderListVO>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        long userId = UserContext.requireUserId();
        return ApiResponse.ok(queryService.buyerList(userId, status, page, pageSize));
    }

    @GetMapping("/api/v1/orders/{orderId}")
    public ApiResponse<OrderDetailVO> detail(@PathVariable("orderId") Long orderId) {
        long userId = UserContext.requireUserId();
        return ApiResponse.ok(queryService.buyerDetail(orderId, userId));
    }

    /** 取消订单（仅待付款可取消；已付款 P0 走退款流程）。 */
    @PostMapping("/api/v1/orders/{orderId}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable("orderId") Long orderId,
                                                   @RequestBody(required = false) CancelRequest request) {
        long userId = UserContext.requireUserId();
        var order = lifecycleService.cancelByUser(orderId, userId, request == null ? null : request.reason());
        return ApiResponse.ok(Map.of(
                "orderId", order.getId(),
                "orderNo", order.getOrderNo(),
                "status", OrderStatus.CANCELLED.getValue(),
                "statusText", OrderStatus.CANCELLED.getText()));
    }

    /** 确认收货（待收货 → 已完成，联动库存出库）。 */
    @PostMapping("/api/v1/orders/{orderId}/confirm")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable("orderId") Long orderId) {
        long userId = UserContext.requireUserId();
        var order = lifecycleService.confirmReceive(orderId, userId);
        return ApiResponse.ok(Map.of(
                "orderId", order.getId(),
                "orderNo", order.getOrderNo(),
                "status", OrderStatus.COMPLETED.getValue(),
                "statusText", OrderStatus.COMPLETED.getText()));
    }
}
