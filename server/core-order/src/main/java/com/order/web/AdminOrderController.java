package com.order.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.api.UserContext;
import com.easyshopping.common.idempotent.Idempotent;
import com.order.domain.OrderStatus;
import com.order.dto.OrderDtos.AdminOrderVO;
import com.order.dto.OrderDtos.ShipRequest;
import com.order.service.OrderLifecycleService;
import com.order.service.OrderQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端订单接口（admin-bff 聚合调用；契约 plans/admin-backend.md 4.2.1）。
 *
 * <p>路径以 plans 为准（{@code /api/admin/order/...}），同时登记任务约定别名
 * {@code /api/admin/orders...}，两个入口指向同一实现。</p>
 *
 * <ul>
 *   <li>{@code GET  /api/admin/order/list} — 分页列表（orderStatus/storeId/orderNo/userId 筛选，includeChildren 展开子单）</li>
 *   <li>{@code GET  /api/admin/order/{id}} — 详情（含明细/子单/金额）</li>
 *   <li>{@code POST /api/admin/order/{id}/ship} — 发货（@Idempotent，重复发货拒绝）</li>
 *   <li>{@code POST /api/admin/order/{id}/close} — 手动关闭（联动释放库存；已支付需退款处置）</li>
 * </ul>
 */
@RestController
public class AdminOrderController {

    private final OrderQueryService queryService;
    private final OrderLifecycleService lifecycleService;

    public AdminOrderController(OrderQueryService queryService, OrderLifecycleService lifecycleService) {
        this.queryService = queryService;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping({"/api/admin/order/list", "/api/admin/orders"})
    public ApiResponse<PageResult<AdminOrderVO>> list(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "orderStatus", required = false) Integer orderStatus,
            @RequestParam(value = "storeId", required = false) Long storeId,
            @RequestParam(value = "orderNo", required = false) String orderNo,
            @RequestParam(value = "includeChildren", defaultValue = "false") boolean includeChildren,
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        requireAdminRole();
        return ApiResponse.ok(queryService.adminList(userId, orderStatus, storeId, orderNo,
                includeChildren, pageNum, pageSize));
    }

    @GetMapping({"/api/admin/order/{orderId}", "/api/admin/orders/{orderId}"})
    public ApiResponse<AdminOrderVO> detail(@PathVariable("orderId") Long orderId) {
        requireAdminRole();
        return ApiResponse.ok(queryService.adminDetail(orderId));
    }

    /** 发货（写接口幂等键；重复发货被状态机拒绝，见 admin-backend 4.2.1）。 */
    @Idempotent
    @PostMapping({"/api/admin/order/{orderId}/ship", "/api/admin/orders/{orderId}/ship"})
    public ApiResponse<Map<String, Object>> ship(@PathVariable("orderId") Long orderId,
                                                 @RequestBody(required = false) ShipRequest request) {
        Long adminId = requireAdminRole();
        var order = lifecycleService.ship(orderId, adminId, request == null ? null : request.trackingNo());
        return ApiResponse.ok(Map.of("orderId", order.getId(), "orderNo", order.getOrderNo(),
                "status", OrderStatus.SHIPPED.getValue(), "statusText", OrderStatus.SHIPPED.getText()));
    }

    /** 手动关单（走与超时关单同一链路：CAS + 释放库存 + 退款处置）。 */
    @PostMapping({"/api/admin/order/{orderId}/close", "/api/admin/orders/{orderId}/close"})
    public ApiResponse<Map<String, Object>> close(@PathVariable("orderId") Long orderId,
                                                  @RequestBody(required = false) Map<String, String> body) {
        Long adminId = requireAdminRole();
        String reason = body == null ? null : body.get("reason");
        var order = lifecycleService.closeByAdmin(orderId, adminId, reason);
        return ApiResponse.ok(Map.of("orderId", order.getId(), "orderNo", order.getOrderNo(),
                "status", order.getOrderStatus()));
    }

    /**
     * 管理端角色校验（DEV.md 4.5：业务服务不重复验 Token，只读角色头）。
     * 角色头为空视为内网直连（P0 信任内网）；显式携带买家角色则拒绝。
     */
    private Long requireAdminRole() {
        List<String> roles = UserContext.roles();
        // 角色头为空 = 内部直连（P0 信任内网）放行；有角色头则要求管理员（兼容 ADMIN/ROLE_ADMIN）
        if (!roles.isEmpty() && !UserContext.isAdmin()) {
            throw new BizException(BizErrorCode.NO_PERMISSION, "需要管理员角色");
        }
        return UserContext.userId();
    }
}
