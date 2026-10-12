package com.order.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.event.DomainEventNames;
import com.easyshopping.common.event.DomainEventPublisher;
import com.order.client.InventoryClient;
import com.order.client.PaymentClient;
import com.order.domain.OrderMain;
import com.order.domain.OrderStatus;
import com.order.domain.OrderStatusLog.Operator;
import com.order.dto.OrderDtos.PaidResponse;
import com.order.repo.OrderMainRepository;
import com.order.support.AfterCommitEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;

/**
 * 订单生命周期操作：支付回调 / 买家取消 / 确认收货 / 后台发货 / 后台关闭 / 超时关单。
 *
 * <p>所有状态变更走 {@link OrderStateMachine#transition} 唯一入口；本服务负责
 * 业务前置校验、库存联动（core-inventory）、资金处置（core-payment TODO）与领域事件。</p>
 *
 * <p><b>关单与支付回调竞态（android-backend 4.2.4）</b>：以订单 {@code version} CAS 裁决，先到者胜——
 * 超时关单赢 → 订单保持已取消，迟到的成功回调触发原路退款（不得回写为已支付）；
 * 支付回调赢 → 关单扫描重读发现非待付款，静默跳过、不释放库存。</p>
 */
@Service
public class OrderLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleService.class);

    /** 支付回调处理结果。 */
    public enum PaidOutcome {
        /** 已入账（待付款 → 待发货）。 */
        PAID,
        /** 幂等重放：订单已是待发货。 */
        ALREADY_PAID,
        /** CAS 败方补偿：订单已关单，已触发原路退款。 */
        REFUND_TRIGGERED
    }

    /** 超时关单结果。 */
    public enum CloseOutcome {
        /** 已关单（已取消 + 释放库存 + 发事件）。 */
        CLOSED,
        /** 跳过：支付回调已先到（订单已支付），不得关单/释放。 */
        SKIPPED
    }

    private final OrderMainRepository orderMainRepository;
    private final OrderStateMachine stateMachine;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final DomainEventPublisher eventPublisher;
    private final TransactionTemplate txTemplate;

    public OrderLifecycleService(OrderMainRepository orderMainRepository,
                                 OrderStateMachine stateMachine,
                                 InventoryClient inventoryClient,
                                 PaymentClient paymentClient,
                                 DomainEventPublisher eventPublisher,
                                 PlatformTransactionManager transactionManager) {
        this.orderMainRepository = orderMainRepository;
        this.stateMachine = stateMachine;
        this.inventoryClient = inventoryClient;
        this.paymentClient = paymentClient;
        this.eventPublisher = eventPublisher;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ 支付成功回调

    /**
     * 支付成功回调（core-payment → {@code POST /api/v1/internal/orders/{orderId}/paid}）。
     *
     * <p>幂等：重复回调返回 ALREADY_PAID。竞态：与超时关单并发时 CAS 裁决，
     * 已关单收到回调 → 自动原路退款（{@code order.refund.applied}）。</p>
     */
    public PaidResponse markPaid(Long orderId, Integer payType) {
        OrderMain parent = parentOf(orderId);
        for (int attempt = 1; attempt <= 3; attempt++) {
            OrderMain current = orderMainRepository.findById(parent.getId())
                    .orElseThrow(() -> new BizException(BizErrorCode.ORDER_NOT_FOUND, "订单不存在"));
            OrderStatus status = OrderStatus.of(current.getOrderStatus());

            if (status == OrderStatus.PAID) {
                return new PaidResponse(parent.getId(), parent.getOrderNo(), PaidOutcome.ALREADY_PAID.name(),
                        OrderStatus.PAID.getValue());
            }
            if (status == OrderStatus.CANCELLED || status == OrderStatus.CLOSED) {
                // 竞态败方补偿：已关单收到成功回调，不得回写为已支付，走原路退款
                triggerRefund(current, "已关单(" + status.getText() + ")收到支付成功回调，自动原路退款");
                return new PaidResponse(parent.getId(), parent.getOrderNo(), PaidOutcome.REFUND_TRIGGERED.name(),
                        status.getValue());
            }
            if (status != OrderStatus.UNPAID) {
                throw new BizException(BizErrorCode.ORDER_STATUS_ERROR,
                        "当前状态「" + status.getText() + "」不接受支付回调");
            }

            boolean changed;
            try {
                changed = stateMachine.transition(parent.getId(), OrderStatus.PAID,
                        Operator.system(), "支付成功回调 payType=" + payType);
            } catch (BizException e) {
                if (e.getErrorCode() != BizErrorCode.ORDER_STATUS_ERROR) {
                    throw e;
                }
                // CAS 输给并发流转（如超时关单），回到循环顶部重读裁决
                log.warn("支付回调 CAS 冲突，重读裁决: orderId={} attempt={} cause={}",
                        parent.getId(), attempt, e.getMsg());
                continue;
            }
            if (changed) {
                Instant now = Instant.now();
                txTemplate.executeWithoutResult(s -> {
                    orderMainRepository.fillPayInfo(parent.getId(), payType, now, now);
                    orderMainRepository.fillPayInfoByParent(parent.getOrderNo(), payType, now, now);
                });
                AfterCommitEvents.publish(eventPublisher, DomainEventNames.ORDER_PAID, Map.of(
                        "orderId", parent.getId(),
                        "orderNo", parent.getOrderNo(),
                        "userId", parent.getUserId(),
                        "payAmount", parent.getPayAmount(),
                        "payType", payType == null ? 0 : payType));
                return new PaidResponse(parent.getId(), parent.getOrderNo(), PaidOutcome.PAID.name(),
                        OrderStatus.PAID.getValue());
            }
            // CAS 输了（并发流转），回到循环顶部重读裁决
            log.warn("支付回调 CAS 未生效，重读裁决: orderId={} attempt={}", parent.getId(), attempt);
        }
        throw new BizException(BizErrorCode.ORDER_STATUS_ERROR, "支付回调处理冲突，请查单后确认");
    }

    // ------------------------------------------------------------------ 买家取消 / 确认收货

    /** 买家取消（仅待付款；已付款走退款流程）。 */
    public OrderMain cancelByUser(Long orderId, Long userId, String reason) {
        OrderMain parent = ownedParent(orderId, userId);
        OrderStatus status = OrderStatus.of(parent.getOrderStatus());
        if (status != OrderStatus.UNPAID) {
            throw new BizException(BizErrorCode.ORDER_STATUS_ERROR, "仅待付款订单可取消，当前「" + status.getText() + "」");
        }
        stateMachine.transition(parent.getId(), OrderStatus.CANCELLED, Operator.user(userId),
                "用户取消" + (reason == null || reason.isBlank() ? "" : ": " + reason));
        fillCloseReason(parent, "manual");
        releaseQuietly(parent, "买家取消");
        AfterCommitEvents.publish(eventPublisher, DomainEventNames.ORDER_CANCELLED, Map.of(
                "orderId", parent.getId(), "orderNo", parent.getOrderNo(),
                "userId", parent.getUserId(), "reason", "user-cancel"));
        return parent;
    }

    /** 确认收货（待收货 → 已完成；出库扣减库存）。 */
    public OrderMain confirmReceive(Long orderId, Long userId) {
        OrderMain parent = ownedParent(orderId, userId);
        // 前置校验（而非依赖状态机抛错）：仅待收货可确认，重复确认明确拒绝、不重复扣减
        OrderStatus status = OrderStatus.of(parent.getOrderStatus());
        if (status != OrderStatus.SHIPPED) {
            throw new BizException(BizErrorCode.ORDER_STATUS_ERROR,
                    "仅待收货订单可确认收货，当前「" + status.getText() + "」");
        }
        stateMachine.transition(parent.getId(), OrderStatus.COMPLETED, Operator.user(userId), "确认收货");
        Instant now = Instant.now();
        txTemplate.executeWithoutResult(s -> {
            orderMainRepository.fillReceiveTime(parent.getId(), now);
            orderMainRepository.fillReceiveTimeByParent(parent.getOrderNo(), now);
        });
        inventoryClient.deduct(parent.getId()); // 幂等：重复确认不会重复扣减
        // 注：30 事件注册表（DomainEventNames / 04-messaging §7.1）暂无 order.completed 常量，
        // 完成通知由状态机的 order.status.changed（含 from/to，to=3 即已完成）承载；
        // 待注册表登记新事件后在此补发（DEV.md 4.4：事件名只能用常量，禁止自造字符串）。
        return parent;
    }

    // ------------------------------------------------------------------ 管理端发货 / 关闭

    /** 后台发货（待发货 → 待收货；重复发货幂等空操作，不重复写发货时间/流水）。 */
    public OrderMain ship(Long orderId, Long adminId, String trackingNo) {
        OrderMain parent = parentOf(orderId);
        if (OrderStatus.of(parent.getOrderStatus()) == OrderStatus.SHIPPED) {
            return parent; // 幂等：已发货直接无操作
        }
        stateMachine.transition(parent.getId(), OrderStatus.SHIPPED, Operator.admin(adminId),
                "发货" + (trackingNo == null || trackingNo.isBlank() ? "" : " trackingNo=" + trackingNo));
        Instant shippedAt = Instant.now();
        txTemplate.executeWithoutResult(s -> {
            orderMainRepository.fillDeliveryTime(parent.getId(), shippedAt);
            orderMainRepository.fillDeliveryTimeByParent(parent.getOrderNo(), shippedAt);
        });
        // TODO(P1): 物流单号/承运商落 logistics 服务并订阅 LOGISTICS_UPDATE 事件推送买家端
        // 注：注册表暂无 order.shipped 常量，发货通知由 order.status.changed（from/to）承载，
        // 登记后在此补发（见 confirmReceive 同款说明）。
        return parent;
    }

    /**
     * 后台手工关单（admin-backend 4.2.1 / 4.4.4 人工兜底，走与超时关单同一链路）。
     *
     * <ul>
     *   <li>待付款 → 已取消（无资金流）+ 释放库存；</li>
     *   <li>待发货（已支付）→ 已关闭 + 释放未出库的锁定库存 + 原路退款（4.3 规则 2：closed 必须有资金处置记录）；</li>
     *   <li>已发货 → 拒绝（需先走售后）。</li>
     * </ul>
     */
    public OrderMain closeByAdmin(Long orderId, Long adminId, String reason) {
        OrderMain parent = parentOf(orderId);
        OrderStatus status = OrderStatus.of(parent.getOrderStatus());
        String note = "后台手工关闭" + (reason == null || reason.isBlank() ? "" : ": " + reason);
        switch (status) {
            case UNPAID -> {
                stateMachine.transition(parent.getId(), OrderStatus.CANCELLED, Operator.admin(adminId), note);
                fillCloseReason(parent, "manual");
                releaseQuietly(parent, "后台关闭");
                AfterCommitEvents.publish(eventPublisher, DomainEventNames.ORDER_CANCELLED, Map.of(
                        "orderId", parent.getId(), "orderNo", parent.getOrderNo(),
                        "userId", parent.getUserId(), "reason", "admin-close"));
            }
            case PAID -> {
                stateMachine.transition(parent.getId(), OrderStatus.CLOSED, Operator.admin(adminId), note);
                fillCloseReason(parent, "manual");
                releaseQuietly(parent, "后台关闭（已支付未发货，锁定库存回补）");
                triggerRefund(parent, "后台关闭已支付订单，原路退款");
            }
            default -> throw new BizException(BizErrorCode.ORDER_STATUS_ERROR,
                    "仅待付款/待发货订单可后台关闭，已发货订单请先走售后（当前「" + status.getText() + "」）");
        }
        return parent;
    }

    // ------------------------------------------------------------------ 超时关单（调度入口）

    /**
     * 超时关单（pay_deadline 已过的待付款订单；调度器每 10s 扫 {@code status=1 AND pay_deadline < now}）。
     *
     * <p>CAS 裁决：与支付回调并发时先到者胜——本方法输则跳过（不释放库存、不发事件）。</p>
     */
    public CloseOutcome closeTimedOut(Long orderId) {
        OrderMain parent = parentOf(orderId);
        OrderStatus status = OrderStatus.of(parent.getOrderStatus());
        if (status != OrderStatus.UNPAID) {
            // 支付回调先到（或已被取消），跳过：拿缓存状态直接流转是禁止的（4.4.1 使用约束）
            log.info("超时关单跳过（非待付款）: orderNo={} status={}", parent.getOrderNo(), status);
            return CloseOutcome.SKIPPED;
        }
        boolean changed;
        try {
            changed = stateMachine.transition(parent.getId(), OrderStatus.CANCELLED,
                    Operator.system(), "超时未支付自动关单");
        } catch (BizException e) {
            if (e.getErrorCode() == BizErrorCode.ORDER_STATUS_ERROR) {
                log.info("超时关单 CAS 败给并发流转，跳过: orderNo={} cause={}", parent.getOrderNo(), e.getMsg());
                return CloseOutcome.SKIPPED;
            }
            throw e;
        }
        if (!changed) {
            return CloseOutcome.SKIPPED;
        }
        fillCloseReason(parent, "timeout");
        releaseQuietly(parent, "超时关单");
        AfterCommitEvents.publish(eventPublisher, DomainEventNames.ORDER_CANCELLED, Map.of(
                "orderId", parent.getId(), "orderNo", parent.getOrderNo(),
                "userId", parent.getUserId(), "reason", "timeout"));
        return CloseOutcome.CLOSED;
    }

    // ------------------------------------------------------------------ 内部工具

    /** 关单后补偿：已关单收到成功回调 → 原路退款 + 发 order.refund.applied（竞态败方，4.2.4）。 */
    private void triggerRefund(OrderMain order, String reason) {
        try {
            paymentClient.refund(order.getId(), order.getPayAmount(), reason);
        } catch (Exception e) {
            // 退款通道异常：必须告警并由对账任务补偿（admin-backend 13.2.2），禁止手工改单
            log.error("退款调用失败，需对账补偿: orderNo={} amount={}", order.getOrderNo(), order.getPayAmount(), e);
        }
        AfterCommitEvents.publish(eventPublisher, DomainEventNames.ORDER_REFUND_APPLIED, Map.of(
                "orderId", order.getId(), "orderNo", order.getOrderNo(),
                "userId", order.getUserId(), "amount", order.getPayAmount(), "reason", reason));
    }

    /** 释放预占库存（inventory 侧幂等，重复释放不会白送库存）；失败不回滚关单，留给对账补偿。 */
    private void releaseQuietly(OrderMain parent, String scene) {
        try {
            inventoryClient.release(parent.getId());
        } catch (Exception e) {
            log.error("释放库存失败（订单已流转，需对账补偿）: scene={} orderNo={}", scene, parent.getOrderNo(), e);
        }
    }

    private void fillCloseReason(OrderMain parent, String reason) {
        Instant now = Instant.now();
        txTemplate.executeWithoutResult(s -> {
            orderMainRepository.fillCloseReason(parent.getId(), reason, now);
            orderMainRepository.fillCloseReasonByParent(parent.getOrderNo(), reason, now);
        });
    }

    /** 归一到父单（拆单后买家/支付/调度侧均以父单为操作对象）。 */
    OrderMain parentOf(Long orderId) {
        OrderMain order = orderMainRepository.findById(orderId)
                .orElseThrow(() -> new BizException(BizErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderId));
        if (order.isParent()) {
            return order;
        }
        return orderMainRepository.findByOrderNo(order.getParentOrderNo())
                .orElseThrow(() -> new BizException(BizErrorCode.ORDER_NOT_FOUND,
                        "父订单不存在: " + order.getParentOrderNo()));
    }

    /** 越权防护（ios-backend 4.4：订单查询/操作一律校验 user_id 归属，防 IDOR）。 */
    OrderMain ownedParent(Long orderId, Long userId) {
        OrderMain parent = parentOf(orderId);
        if (parent.getUserId() == null || !parent.getUserId().equals(userId)) {
            throw new BizException(BizErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderId);
        }
        return parent;
    }
}
