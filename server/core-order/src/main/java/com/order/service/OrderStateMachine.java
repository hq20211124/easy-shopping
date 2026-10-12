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
 * <p>禁止在任何业务代码里直接 {@code update order set order_status=...}。每次流转的执行顺序：</p>
 * <ol>
 *   <li><b>终态拦截</b>（{@code cancelled/closed} 不允许再流出）——必须<b>先于</b>幂等短路，
 *       否则终态订单的「同状态重复请求」会被当成幂等重放静默判成功（假成功）；</li>
 *   <li>幂等：父单已处于目标状态时不再 CAS，但仍扫描子单<b>自愈父子漂移</b>（补齐落后的子单并写流水），
 *       而不是直接 {@code return false}；</li>
 *   <li>白名单校验（4.3 流转白名单，父单与每个子单各校验一次）；</li>
 *   <li>{@code version} 乐观锁 CAS 条件更新（{@code WHERE version=? AND order_status=?}），
 *       并发（超时关单 vs 支付回调 vs 手工操作）只允许一方成功，输的一方由调用方按业务语义补偿；</li>
 *   <li><b>组内一致性断言</b>：整组（父 + 全部子单）必须都处于目标状态，否则抛 4002 整体回滚——
 *       杜绝「父单成功 + 子单没跟上」的半成功状态；</li>
 *   <li>写状态流水（from/to/操作人/原因）——父子单各一条，保证 timeline 与对账可追溯；</li>
 *   <li>事务提交后发 {@code order.status.changed} 顺序事件（orderId Hash 保证同一订单事件有序）。</li>
 * </ol>
 *
 * <p><b>父子级联</b>：拆单后父子状态必须一致；对子单调用等价于对父单流转并级联所有子单
 * （整组在一个事务内，任一 CAS 失败或任何一致性断言失败都整体回滚）。</p>
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
     * <p>执行顺序：<b>终态拦截 → 同状态幂等（含父子漂移自愈）→ 白名单 → 整组 CAS → 组内一致性断言</b>。</p>
     *
     * @param orderId  订单 ID（父单或子单皆可，子单自动归一到父单并级联）
     * @param target   目标状态
     * @param operator 操作人（1用户 2商家 3系统 4客服）
     * @param reason   流转原因（落流水）
     * @return true=本次确实发生了状态变更（父单 CAS 成功，或自愈补齐了漂移的子单）；
     *         false=整组已在目标状态且无漂移（幂等空操作）
     * @throws BizException 4001 订单不存在 / 4002 终态拦截、白名单外、CAS 并发失败、组内状态漂移无法补齐
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

        // ① 终态拦截先于幂等短路：cancelled/closed 的「同状态重复请求」不是幂等重放而是非法请求。
        //    若先 return false，终态订单的任何重复请求都会被静默判成功（假成功，无法与真幂等区分）。
        assertNotTerminal(from);

        // ② 幂等（4.4.1 第 2 步）：父单已在目标状态 → 不再对父单 CAS，但仍扫描子单自愈父子漂移。
        //    不能直接 return false：那样历史数据/人工改库造成的父子漂移永远不会被补齐。
        if (from == target) {
            int healed = healDriftedChildren(group, target, operator, reason);
            if (healed == 0) {
                return false;
            }
            assertGroupConsistent(group, target, parent.getOrderNo());
            // 父单状态未变故不发 order.status.changed（from==to 的事件会让下游误判为状态流转）；
            // 补齐动作本身有 WARN 日志 + 状态流水可追溯，返回 true 让调用方继续执行幂等的后置动作。
            log.warn("父子漂移自愈完成（父单状态未变）: parentNo={} healed={}", parent.getOrderNo(), healed);
            return true;
        }
        validate(from, target, operator);

        boolean changed = false;
        int adopted = 0; // 未由本事务推进、但重读确认已在目标态的成员（并发方抢先 / 此前已同步）
        Instant now = Instant.now();
        for (OrderMain current : group) {
            OrderStatus currentFrom = OrderStatus.of(current.getOrderStatus());
            if (currentFrom == target) {
                adopted++; // 子单已同步过（重试/补写场景）；同样要过末尾的一致性断言
                continue;
            }
            validate(currentFrom, target, operator);
            int rows = orderMainRepository.casUpdateStatus(
                    current.getId(), currentFrom.getValue(), target.getValue(), current.getVersion(), now);
            if (rows == 0) {
                // CAS 输了：重读裁决——并发方已推到目标状态则「按已同步」计入并交由末尾的一致性断言复核，
                // 否则报状态冲突由 @Transactional 整体回滚（禁止半成功）。
                OrderMain fresh = load(current.getId());
                OrderStatus freshStatus = OrderStatus.of(fresh.getOrderStatus());
                if (freshStatus != target) {
                    throw new BizException(BizErrorCode.ORDER_STATUS_ERROR, "订单状态已变更，请刷新后重试");
                }
                log.warn("状态 CAS 败方：重读确认已被并发方推到目标态（转入组内一致性断言复核）: orderNo={} {} -> {} version={}",
                        current.getOrderNo(), currentFrom, target, fresh.getVersion());
                adopted++;
                continue;
            }
            changed = true;
            writeStatusLog(current, currentFrom, target, operator, reason);
            log.info("订单状态流转: orderNo={} {} -> {} reason={} operatorType={}",
                    current.getOrderNo(), currentFrom, target, reason, operator.type());
        }

        if (adopted > 0) {
            log.info("订单组中 {} 条记录非本事务推进（重读已确认处于目标态）: parentNo={} target={}",
                    adopted, parent.getOrderNo(), target);
        }
        // ⑤ 组内一致性断言：整组必须都在目标状态，否则整体回滚（不允许「父单成功、子单没跟上」）。
        assertGroupConsistent(group, target, parent.getOrderNo());

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

    /**
     * 父子漂移自愈：父单已在目标状态时，把组内落后的子单补齐（CAS + 流水）。
     *
     * <p>正常路径下父子同事务更新不会漂移；漂移来源是历史数据 / 人工改库 / 外部脚本。
     * 补不齐（子单处于终态或白名单外）时抛 4002 —— 让数据问题暴露，而不是被「幂等成功」掩盖。</p>
     *
     * @return 本次补齐的子单数；0 = 组内本就一致
     */
    private int healDriftedChildren(List<OrderMain> group, OrderStatus target, Operator operator, String reason) {
        int healed = 0;
        Instant now = Instant.now();
        String healReason = (reason == null || reason.isBlank() ? "" : reason + " - ") + "子单状态漂移自愈";
        for (OrderMain child : group) {
            if (child.isParent()) {
                continue; // 父单已处于目标状态（调用前提）
            }
            OrderStatus childFrom = OrderStatus.of(child.getOrderStatus());
            if (childFrom == target) {
                continue; // 已一致
            }
            validate(childFrom, target, operator); // 不可合法补齐 → 抛 4002，暴露数据漂移
            int rows = orderMainRepository.casUpdateStatus(child.getId(), childFrom.getValue(),
                    target.getValue(), child.getVersion(), now);
            if (rows == 0) {
                OrderMain fresh = load(child.getId());
                if (OrderStatus.of(fresh.getOrderStatus()) != target) {
                    throw new BizException(BizErrorCode.ORDER_STATUS_ERROR,
                            "子单状态已变更，自愈失败，请刷新后重试: orderNo=" + child.getOrderNo());
                }
                continue; // 并发方已补齐
            }
            healed++;
            writeStatusLog(child, childFrom, target, operator, healReason);
            log.warn("父子漂移自愈: childNo={} {} -> {} operatorType={}",
                    child.getOrderNo(), childFrom, target, operator.type());
        }
        return healed;
    }

    /**
     * 组内一致性断言（4.4.1「拆单后父子状态必须一致」）：重读整组，任一成员不在目标态即抛 4002。
     *
     * <p>CAS 败方 {@code continue} 分支无法区分「被并发方推进」与「数据异常」，
     * 因此在返回/提交前做一次断言兜底：断言失败即整体回滚，杜绝半成功状态被提交。</p>
     *
     * <p>读一致性说明：{@code casUpdateStatus} 带 {@code clearAutomatically/flushAutomatically=true}，
     * 每条 CAS 后都会清空一级缓存，本方法的重读会真正打到数据库（含本事务的未提交写入）。</p>
     */
    private void assertGroupConsistent(List<OrderMain> group, OrderStatus target, String parentOrderNo) {
        for (OrderMain member : group) {
            OrderStatus actual = OrderStatus.of(load(member.getId()).getOrderStatus());
            if (actual != target) {
                log.error("订单组状态漂移，整体回滚（禁止半成功）: parentNo={} orderNo={} actual={} expected={}",
                        parentOrderNo, member.getOrderNo(), actual, target);
                throw new BizException(BizErrorCode.ORDER_STATUS_ERROR,
                        "订单组状态不一致（orderNo=" + member.getOrderNo() + " 处于「" + actual.getText()
                                + "」），请刷新后重试");
            }
        }
    }

    /** 写状态流水（每次 CAS 成功一条；父子各一条）。 */
    private void writeStatusLog(OrderMain order, OrderStatus from, OrderStatus target,
                                Operator operator, String reason) {
        orderStatusLogRepository.save(OrderStatusLog.create(
                idGenerator.nextId(), order.getId(), order.getOrderNo(),
                from.getValue(), target.getValue(), operator, reason));
    }

    /** 白名单 + 终态拦截（4.4.1 第 1 步，v1.2 显式终态声明）。 */
    private void validate(OrderStatus from, OrderStatus target, Operator operator) {
        assertNotTerminal(from);
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

    /** 终态拦截：cancelled / closed 不允许任何流出（含「同状态重复请求」）。 */
    private void assertNotTerminal(OrderStatus from) {
        if (TERMINAL_STATES.contains(from)) {
            throw new BizException(BizErrorCode.ORDER_STATUS_ERROR,
                    "订单已处于终态「" + from.getText() + "」，禁止再流转");
        }
    }

    private OrderMain load(Long orderId) {
        return orderMainRepository.findById(orderId)
                .orElseThrow(() -> new BizException(BizErrorCode.ORDER_NOT_FOUND, "订单不存在: " + orderId));
    }
}
