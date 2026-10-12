package com.order.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.api.UserContext;
import com.easyshopping.common.idempotent.Idempotent;
import com.order.domain.OrderStatus;
import com.order.dto.OrderDtos.AdminOrderVO;
import com.order.dto.OrderDtos.ReceiverMobileView;
import com.order.dto.OrderDtos.ShipRequest;
import com.order.service.OrderLifecycleService;
import com.order.service.OrderQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
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
 *   <li>{@code GET  /api/admin/order/{id}} — 详情（含明细/子单/金额；手机号脱敏）</li>
 *   <li>{@code POST /api/admin/order/{id}/ship} — 发货（@Idempotent，重复发货拒绝）</li>
 *   <li>{@code POST /api/admin/order/{id}/close} — 手动关闭（联动释放库存；已支付需退款处置）</li>
 *   <li>{@code GET  /api/admin/order/{orderNo}/receiver-mobile} — <b>明文手机号专用接口</b>
 *       （专门权限码 {@link #PERM_RECEIVER_MOBILE_READ} + 必留痕，见 {@code receiverMobile()}）</li>
 * </ul>
 */
@RestController
public class AdminOrderController {

    /**
     * 查看收货人明文手机号的<b>专门权限码</b>。
     *
     * <p>不复用 {@code order:detail}/{@code order:ship}：明文 PII 是独立的高敏操作，
     * 必须能单独授权/单独回收（默认最小授权原则）。admin-bff 的 {@code DataInitializer}
     * 需在订单菜单下注册同名权限点，RBAC 侧同名。</p>
     */
    public static final String PERM_RECEIVER_MOBILE_READ = "order:mobile:read";

    /**
     * admin-bff 完成本地 RBAC 校验后，向 core 透传的权限码集合（英文逗号分隔）。
     *
     * <p><b>为什么 core-order 还要再校验一次</b>（plans/admin-backend.md 2.3.2「双重校验」）：
     * P0 业务服务不验 Token，但这只覆盖「是不是管理员」，不覆盖「这个管理员有没有 PII 权限点」；
     * 网关/直连旁路一旦被绕过，本接口若无独立闸门即等于明文全开。
     * <b>缺头即拒绝（fail-closed）</b>：宁可管理端暂时报 1004，也不能因为少传一个头就默认放行。</p>
     */
    public static final String HEADER_ADMIN_PERMS = "X-Admin-Perms";

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
     * 管理端查看收货人<b>明文</b>手机号（发货 / 联系快递场景）。
     *
     * <p><b>三重闸门（缺一不可）</b>：① 管理员角色（{@code requireAdminRole()}）；
     * ② 专门权限码 {@link #PERM_RECEIVER_MOBILE_READ}（fail-closed，缺头/不含该码 → 1004）；
     * ③ 操作人身份非空（无操作人则留痕无意义 → 1002）。
     * 三者通过后由 {@link OrderQueryService#receiverMobileForShip} 写一条操作留痕流水，
     * <b>返回体只有本单明文，无列表/导出入口</b>。</p>
     *
     * <p><b>为什么按 orderNo 而不是 orderId 定位</b>：对外可追溯的标识是 24 位订单号
     * （AGENTS.md 编码口径），且避免父单/子单两个雪花 ID 造成的歧义。</p>
     *
     * @param orderNo 订单号（24 位：时间14 + 槽位4 + 序列6）
     * @param permCodes admin-bff 透传的权限码集合（见 {@link #HEADER_ADMIN_PERMS}）
     */
    @GetMapping({"/api/admin/order/{orderNo}/receiver-mobile", "/api/admin/orders/{orderNo}/receiver-mobile"})
    public ApiResponse<ReceiverMobileView> receiverMobile(
            @PathVariable("orderNo") String orderNo,
            @RequestHeader(value = HEADER_ADMIN_PERMS, required = false) String permCodes) {
        requireAdminRole();
        requirePerm(permCodes, PERM_RECEIVER_MOBILE_READ);
        Long operatorId = requireOperator();
        return ApiResponse.ok(queryService.receiverMobileForShip(orderNo, operatorId));
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

    /**
     * 权限码校验（逗号分隔，大小写不敏感，容忍空白）。
     *
     * <p><b>fail-closed</b>：头缺失或为空一律拒绝。P0 的「空角色头=内网直连放行」口径
     * <b>不适用于</b>本接口——明文 PII 不能靠「没带头」就默认放行。</p>
     */
    private void requirePerm(String permCodes, String required) {
        if (permCodes == null || permCodes.isBlank()) {
            throw new BizException(BizErrorCode.NO_PERMISSION, "缺少权限：" + required);
        }
        boolean matched = Arrays.stream(permCodes.split(","))
                .map(String::trim)
                .anyMatch(required::equalsIgnoreCase);
        if (!matched) {
            throw new BizException(BizErrorCode.NO_PERMISSION, "缺少权限：" + required);
        }
    }

    /** 操作人身份（X-User-Id）：明文查看必须留痕，无操作人即无留痕。 */
    private Long requireOperator() {
        Long operatorId = UserContext.userId();
        if (operatorId == null) {
            throw new BizException(BizErrorCode.NOT_LOGIN, "查看收货人明文手机号需要管理员身份（用于操作留痕）");
        }
        return operatorId;
    }
}
