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

    /**
     * 管理端明文手机号查看结果（{@code GET /api/admin/orders/{orderNo}/receiver-mobile}）。
     *
     * <p><b>只含单条明文，不含任何批量/导出结构</b>——避免一次请求拖走一批手机号。</p>
     *
     * @param orderNo        订单号（前端二次确认用，避免看错单）
     * @param receiverMobile <b>明文</b>收货人手机号（仅本单、仅本权限码、且已留痕）
     */
    public record ReceiverMobileView(String orderNo, String receiverMobile) {
    }

    /**
     * 服务间订单字段快照（{@code GET /api/v1/internal/orders/{orderId}}）。
     *
     * <p><b>字段集由「调用方实际消费了什么」反推得出，只多不少地禁止——每个字段都对应一个已知消费方：</b></p>
     * <table border="1">
     *   <caption>字段与消费方对照</caption>
     *   <tr><th>字段</th><th>消费方</th></tr>
     *   <tr><td>{@code orderId}</td><td>core-payment {@code RestOrderClient#toSnapshot}（firstLong(data,"orderId","id")）</td></tr>
     *   <tr><td>{@code userId}</td><td>core-payment（prepay 归属校验，防付他人订单）</td></tr>
     *   <tr><td>{@code totalAmount}</td><td>core-payment（<b>仅参考</b>，对账/告警；不得用作定额）</td></tr>
     *   <tr><td>{@code payAmount}</td><td>core-payment（<b>支付域定额唯一口径</b>，裁决 C7 防改价）</td></tr>
     *   <tr><td>{@code status}</td><td>core-payment（已取消/已关闭判定）+ core-promotion
     *       {@code RestDeductedOrderVerifier}（6/7 → ORDER_GONE）</td></tr>
     *   <tr><td>{@code payDeadline}</td><td>core-payment（预支付过期时间兜底）</td></tr>
     * </table>
     *
     * <p><b>刻意不返回</b>：{@code receiverMobile}（明文手机号）、{@code receiverName}、
     * {@code receiverAddress}、{@code remark}（收货个人信息，服务间调用方一个都不消费）、
     * {@code couponId}/{@code payType}/{@code version} 等无消费方的字段。
     * 整返 {@code OrderMain} 实体会把这些明文一并开放给 core-payment / core-promotion
     * （审计 P1 遗留项），删除成本极高，故从类型层面杜绝。</p>
     *
     * <p><b>字段名不得改动</b>：core-payment 的解析口径按 {@code orderId}/{@code payAmount}
     * 取值，改名即导致 prepay fail-closed（缺 payAmount → 9999）。</p>
     *
     * @param orderId     订单 ID（core-payment 兼容 orderId/id 两个字段名，这里给 orderId）
     * @param userId      下单用户（prepay 做归属校验）
     * @param totalAmount 商品总额（优惠前，<b>仅供参考</b>，支付域不得用作定额依据）
     * @param payAmount   应付金额（= 商品总额 - 优惠 + 运费）——<b>支付域定额唯一口径</b>（裁决 C7 防改价）
     * @param status      0 基 8 值订单状态（AGENTS.md 唯一事实来源）
     * @param payDeadline 支付截止（订单表为唯一事实来源）
     */
    public record InternalOrderSnapshot(Long orderId, Long userId, BigDecimal totalAmount,
                                        BigDecimal payAmount, Integer status, Instant payDeadline) {

        /** 由订单实体收窄为字段快照（<b>唯一</b>构造入口：新增字段必须先确认有消费方）。 */
        public static InternalOrderSnapshot of(com.order.domain.OrderMain order) {
            return new InternalOrderSnapshot(order.getId(), order.getUserId(),
                    order.getTotalAmount(), order.getPayAmount(),
                    order.getOrderStatus(), order.getPayDeadline());
        }
    }
}
