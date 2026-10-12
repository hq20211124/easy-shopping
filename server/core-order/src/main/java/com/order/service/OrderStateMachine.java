package com.order.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.event.DomainEventNames;
import com.easyshopping.common.event.DomainEventPublisher;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.order.domain.OrderMain;
import com.order.domain.OrderStatus;
import com.order.domain.OrderStatusLog;
import com.order.domain.OrderStatusLog.Operator;
import com.order.repo.OrderMainRepository;
import com.order.repo.OrderStatusLogRepository;
import com.order.support.AfterCommitEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态机：所有状态变更的<b>唯一入口</b>（admin-backend 4.4.1；android-backend 4.3.4 落地约定 4）。
 *
 * <p>禁止在任何业务代码里直接 {@code update order set order_status=...}。每次流转：</p>
 * <ol>
 *   <li>终态拦截（{@code cancelled/closed} 不允许再流出）+ 白名单校验（4.3 流转白名单）；</li>
 *   <li>幂等：已处于目标状态直接返回 false（MQ 重投/支付回调重放安全）；</li>
 *   <li>{@code version} 乐观锁 CAS 条件更新（{@code WHERE version=? AND order_status=?}），
 *       并发（超时关单 vs 支付回调 vs 手工操作）只允许一方成功，输的一方由调用方按业务语义补偿；</li>
 *   <li>写状态流水（from/to/操作人/原因）——父子单各一条，保证 timeline 与对账可追溯；</li>
 *   <li>事务提交后发 {@code order.status.changed} 顺序事件（orderId Hash 保证同一订单事件有序）。</li>
 * </ol>
 *
 * <p><b>父子级联</b>：拆单后父子状态必须一致；对子单调用等价于对父单流转并级联所有子单
 * （整组在一个事务内，任一 CAS 失败整体回滚）。</p>
 */
@Component
public class OrderStateMachine {

    private static final Logger log = LoggerFactory.getLogger(OrderStateMachine.class);

    /** 流转白名单：与 admin-backend 4.3「流转白名单」表逐项一致（8 值 0 基）。 */
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
            OrderStatus.UNPAID, Set.of(OrderStatus.PAID, OrderStatus.CANCELLED),
            OrderStatus.PAID, Set.of(OrderStatus.SHIPPED, OrderStatus.REFUNDING,
                    OrderStatus.CANCELLED, OrderStatus.CLOSED),
            OrderStatus.SHIPPED, Set.of(OrderStatus.COMPLETED, OrderStatus.REFUNDING),
            OrderStatus.COMPLETED, Set.of(OrderStatus.REFUNDING, OrderStatus.CLOSED),
            OrderStatus.REFUNDING, Set.of(OrderStatus.REFUNDED, OrderStatus.COMPLETED, OrderStatus.CLOSED),
            // v1.2 跨文件同步：退款完成 → 系统归档关闭（与买家端 4.3.4「5→7」一致）
            OrderStatus.REFUNDED, Set.of(OrderStatus.CLOSED));

    /** 真正的终态：不允许任何流出（cancelled = 无资金流取消；closed = 已支付后关闭）。 */
    private static final Set<OrderStatus> TERMINAL_STATES = Set.of(OrderStatus.CANCELLED, OrderStatus.CLOSED);

    /** 5→7 归档边仅允许系统任务触发（operator.type=3 系统，对应 plans 的 system:archive）。 */
    private static final int OPERATOR_TYPE_SYSTEM = 3;

    private final OrderMainRepository orderMainRepository;
    private final OrderStatusLogRepository orderStatusLogRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final DomainEventPublisher eventPublisher;

    public OrderStateMachine(OrderMainRepository orderMainRepository,
                             OrderStatusLogRepository orderStatusLogRepository,
                             SnowflakeIdGenerator idGenerator,
                             DomainEventPublisher eventPublisher) {
        this.orderMainRepository = orderMainRepository;
        this.orderStatusLogRepository = orderStatusLogRepository;
        this.idGenerator = idGenerator;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 状态流转（唯一入口）。
     *
     * @param orderId  订单 ID（父单或子单皆可，子单自动归一到父单并级联）
     * @param target   目标状态
     * @param operator 操作人（1用户 2商家 3系统 4客服）
     * @param reason   流转原因（落流水）
     * @return true=本次发生了流转；false=订单已在目标状态（幂等空操作）
     * @throws BizException 4001 订单不存在 / 4002 终态拦截、白名单外、CAS 并发失败
     */
    @Transactional
    public boolean transition(Long orderId, OrderStatus target, Operator operator, String reason) {
        OrderMain order = load(orderId);
        OrderMain parent = order.isParent() ? order
                : orderMainRepository.findByOrderNo(order.getParentOrderNo())
                .orElseThrow(() -> new BizException(BizErrorCode.ORDER_NOT_FOUND,
                        "父订单不存在: " + order.getParentOrderNo()));

        List<OrderMain> group = new ArrayList<>();
        group.add(parent);
        group.addAll(orderMainRepository.findByParentOrderNo(parent.getOrderNo()));

        OrderStatus from = OrderStatus.of(parent.getOrderStatus());
        // 幂等优先（4.4.1 第 2 步）：已处于目标状态直接返回 false（MQ 重投/支付回调重放/重复发货安全），
        // 必须先于白名单校验——否则 from==target 会被白名单误判为非法流转
        if (from == target) {
            return false;
        }
        validate(from, target, operator);

        boolean changed = false;
        Instant now = Instant.now();
        for (OrderMain current : group) {
            OrderStatus currentFrom = OrderStatus.of(current.getOrderStatus());
            if (currentFrom == target) {
                continue; // 子单已同步过（重试/补写场景）
            }
            validate(currentFrom, target, operator);
            int rows = orderMainRepository.casUpdateStatus(
                    current.getId(), currentFrom.getValue(), target.getValue(), current.getVersion(), now);
            if (rows == 0) {
                // CAS 输了：重读裁决——并发方已推到目标状态则跳过，否则报状态冲突
                OrderMain fresh = load(current.getId());
                if (OrderStatus.of(fresh.getOrderStatus()) != target) {
                    throw new BizException(BizErrorCode.ORDER_STATUS_ERROR, "订单状态已变更，请刷新后重试");
                }
                continue;
            }
            changed = true;
            orderStatusLogRepository.save(OrderStatusLog.create(
                    idGenerator.nextId(), current.getId(), current.getOrderNo(),
                    currentFrom.getValue(), target.getValue(), operator, reason));
            log.info("订单状态流转: orderNo={} {} -> {} reason={} operatorType={}",
                    current.getOrderNo(), currentFrom, target, reason, operator.type());
        }

        if (changed) {
            AfterCommitEvents.publish(eventPublisher, DomainEventNames.ORDER_STATUS_CHANGED, Map.of(
                    "orderId", parent.getId(),
                    "orderNo", parent.getOrderNo(),
                    "userId", parent.getUserId(),
                    "fromStatus", from.getValue(),
                    "toStatus", target.getValue()));
        }
        return changed;
    }

    /** 白名单 + 终态拦截（4.4.1 第 1 步，v1.2 显式终态声明）。 */
    private void validate(OrderStatus from, OrderStatus target, Operator operator) {
        if (TERMINAL_STATES.contains(from)) {
            throw new BizException(BizErrorCode.ORDER_STATUS_ERROR,
                    "订单已处于终态「" + from.getText() + "」，禁止再流转");
        }
        Set<OrderStatus> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(target)) {
            throw new BizException(BizErrorCode.ORDER_STATUS_ERROR,
                    "订单状态不允许从「" + from.getText() + "」变更为「" + target.getText() + "」");
        }
        if (from == OrderStatus.REFUNDED && target == OrderStatus.CLOSED && operator.type() != OPERATOR_TYPE_SYSTEM) {
            throw new BizException(BizErrorCode.ORDER_STATUS_ERROR,
                    "已退款 → 已关闭 仅允许财务对账归档任务触发，禁止人工操作");
        }
    }

    private OrderMain load(Long orderId) {
        return orderMainRepository.findById(orderId)
                .orElseThrow(() -> new BizException(BizErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderId));
    }
}
