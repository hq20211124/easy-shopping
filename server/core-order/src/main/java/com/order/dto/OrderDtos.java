package com.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 订单接口 DTO（买家端契约对齐 plans/ios-backend.md 4.2 + plans/android-backend.md 4.3.2；
 * 管理端对齐 plans/admin-backend.md 4.2.1）。
 *
 * <p>金额一律 {@code BigDecimal}、数量 {@code Integer}（禁止浮点）；请求体中的金额字段一律不信任，
 * 由 core-price 实时计价（android-backend 4.1.4「金额权威」）。</p>
 */
public final class OrderDtos {

    private OrderDtos() {
    }

    // ------------------------------------------------------------------ 下单

    /** 下单行项。 */
    public record OrderItemRequest(
            @NotNull(message = "skuId 不能为空") Long skuId,
            @Min(value = 1, message = "quantity 必须大于 0") int quantity) {
    }

    /**
     * 创建订单请求（{@code POST /api/v1/orders}）。
     *
     * <p>收货地址：P0 由客户端直传快照字段（addressId 仅登记，P1 调 core-user 生成快照）；
     * 快照在下单事务内固化，后续改地址不影响历史订单。</p>
     */
    public record CreateOrderRequest(
            @NotEmpty(message = "items 不能为空") @Valid List<@NotNull OrderItemRequest> items,
            Long addressId,
            String receiverName,
            String receiverMobile,
            String receiverAddress,
            Long couponId,
            String remark,
            Integer source) {
    }

    /** 子单视图（拆单结果）。 */
    public record SubOrderView(Long orderId, String orderNo, Long storeId, BigDecimal payAmount, int itemCount) {
    }

    /** 创建订单响应。 */
    public record CreateOrderResponse(Long orderId, String orderNo, int status, String statusText,
                                      BigDecimal totalAmount, BigDecimal discountAmount,
                                      BigDecimal freightAmount, BigDecimal payAmount,
                                      Long expireSeconds, Instant payDeadline,
                                      List<SubOrderView> subOrders) {
    }

    // ------------------------------------------------------------------ 查询

    /** 订单行项视图。 */
    public record OrderItemView(Long orderId, String orderNo, Long storeId, Long spuId, Long skuId,
                                String skuName, String skuImage, BigDecimal price, int quantity,
                                BigDecimal totalAmount, BigDecimal discountAmount) {
    }

    /** 操作按钮（由服务端按状态机下发，前端只渲染，三端文案一致）。 */
    public record ActionView(String type, String text) {
    }

    /** 金额明细。 */
    public record AmountDetailView(BigDecimal goodsAmount, BigDecimal freightAmount,
                                   BigDecimal discountAmount, BigDecimal payAmount) {
    }

    /** 状态流水（timeline 数据源，只读日志不做状态推算）。 */
    public record TimelineView(int fromStatus, int toStatus, String toStatusText,
                               int operatorType, Long operatorId, String reason, Instant time) {
    }

    /** 子单摘要（买家详情页按店铺展示）。 */
    public record ChildOrderView(Long orderId, String orderNo, Long storeId, int status, String statusText,
                                 BigDecimal totalAmount, BigDecimal discountAmount, BigDecimal payAmount,
                                 int itemCount) {
    }
    /** 订单列表项（买家）。 */
    public record OrderListVO(Long orderId, String orderNo, int status, String statusText, String paymentStatus,
                              BigDecimal totalAmount, BigDecimal discountAmount, BigDecimal freightAmount,
                              BigDecimal payAmount, int itemCount, int storeCount,
                              List<OrderItemView> items, Instant payDeadline, Instant createdAt,
                              List<ActionView> actions) {
    }

    /** 订单详情（买家）。 */
    public record OrderDetailVO(Long orderId, String orderNo, int status, String statusText, String paymentStatus,
                                AmountDetailView amountDetail, List<OrderItemView> items,
                                List<ChildOrderView> children,
                                String receiverName, String receiverMobile, String receiverAddress,
                                String remark, Integer payType, Instant payTime, Instant payDeadline,
                                Instant deliveryTime, Instant receiveTime,
                                List<TimelineView> timeline, List<ActionView> actions,
                                Long version, Instant createdAt) {
    }

    // ------------------------------------------------------------------ 操作

    /** 取消订单请求（待付款可取消；已付款 P0 走退款流程不在此接口）。 */
    public record CancelRequest(String reason) {
    }

    /** 支付成功回调请求（core-payment → core-order，内部接口）。 */
    public record PaidRequest(Integer payType) {
    }

    /**
     * 支付成功回调响应。
     *
     * <p>{@code outcome}：{@code PAID} 已入账 / {@code ALREADY_PAID} 幂等重放 /
     * {@code REFUND_TRIGGERED} 已关单收到回调，已触发原路退款（竞态败方补偿，android 4.2.4）。</p>
     */
    public record PaidResponse(Long orderId, String orderNo, String outcome, int status) {
    }

    // ------------------------------------------------------------------ 管理端

    /** 管理端订单列表项（含子单展开）。 */
    public record AdminOrderVO(Long id, String orderNo, Long userId, String parentOrderNo,
                               Long storeId, int status, String statusText, Integer payType, Instant payTime,
                               BigDecimal totalAmount, BigDecimal discountAmount, BigDecimal freightAmount,
                               BigDecimal payAmount, String receiverName, String receiverMobile,
                               String closeReason, String remark, boolean splitFlag, Long version,
                               List<OrderItemView> items, List<ChildOrderView> children, Instant createdAt) {
    }

    /** 管理端发货请求（物流单号，可空）。 */
    public record ShipRequest(String trackingNo) {
    }
}
