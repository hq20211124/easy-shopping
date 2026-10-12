package com.order;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.event.DomainEventNames;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.order.domain.OrderMain;
import com.order.domain.OrderStatus;
import com.order.domain.OrderStatusLog.Operator;
import com.order.repo.OrderMainRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/**
 * 状态机 CAS 败方裁决与「半成功」防护测试（审计 P0-B）。
 *
 * <p>修复前：{@code casUpdateStatus} 返回 0 时，只要重读发现状态已是目标态就 {@code continue}，
 * 于是「父单 CAS 成功 + 子单 continue」会返回 changed=true 并发事件，组内却可能并不一致。</p>
 *
 * <p>修复后：continue 前必须重读确认处于目标态并记 WARN，提交前再做一次<b>组内一致性断言</b>，
 * 断言失败即抛 4002 整体回滚。本类用 {@code @SpyBean} 精确构造 3 种 CAS 结果：</p>
 * <ol>
 *   <li>命中 0 行但行已被并发方推到目标态 → 整组一致，正常提交；</li>
 *   <li>命中 0 行且行仍在旧状态 → 抛 4002 整体回滚；</li>
 *   <li>「假成功」（返回 1 但行未变）→ 末尾一致性断言拦截并整体回滚。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-order-cas-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.order.timeout-scan-ms=3600000",
        "app.order.timeout-scan-initial-ms=3600000"
})
class OrderStateMachineCasTest extends AbstractOrderTest {

    @SpyBean
    private OrderMainRepository repo;

    @Autowired
    private SnowflakeIdGenerator idGenerator;

    private long seedParentWithChild(OrderStatus parentStatus, OrderStatus childStatus) {
        long parentId = idGenerator.nextId();
        OrderMain parent = OrderMain.parent(parentId,
                "202610120002" + String.format("%010d", parentId % 10_000_000_000L), 10086L);
        parent.setTotalAmount(new BigDecimal("100.00"));
        parent.setPayAmount(new BigDecimal("100.00"));
        parent.setPayDeadline(Instant.now().plusSeconds(1800));
        parent.setOrderStatus(parentStatus.getValue());
        orderMainRepository.save(parent);

        long childId = idGenerator.nextId();
        OrderMain child = OrderMain.child(childId,
                "202610120003" + String.format("%010d", childId % 10_000_000_000L), parent, 30001L, parent.getOrderNo());
        child.setTotalAmount(new BigDecimal("40.00"));
        child.setPayAmount(new BigDecimal("40.00"));
        child.setOrderStatus(childStatus.getValue());
        orderMainRepository.save(child);
        return childId;
    }

    private long parentIdOf(long childId) {
        String parentOrderNo = orderMainRepository.findById(childId).orElseThrow().getParentOrderNo();
        return orderMainRepository.findByOrderNo(parentOrderNo).orElseThrow().getId();
    }

    private OrderStatus statusOf(long orderId) {
        return OrderStatus.of(orderMainRepository.findById(orderId).orElseThrow().getOrderStatus());
    }

    @Test
    void CAS败方_重读确认已被并发方推到目标态_整组一致正常提交() {
        long childId = seedParentWithChild(OrderStatus.UNPAID, OrderStatus.UNPAID);
        long parentId = parentIdOf(childId);
        List<Object> events = captureEvents(DomainEventNames.ORDER_STATUS_CHANGED);

        // 模拟并发方抢先一步把子单推到目标态：本事务的 CAS 命中 0 行，但重读已是 PAID
        doAnswer(invocation -> {
            OrderMain concurrent = orderMainRepository.findById(childId).orElseThrow();
            concurrent.setOrderStatus(OrderStatus.PAID.getValue());
            orderMainRepository.saveAndFlush(concurrent);
            return 0;
        }).when(repo).casUpdateStatus(eq(childId), anyInt(), anyInt(), anyInt(), any());

        assertThat(stateMachine.transition(parentId, OrderStatus.PAID, Operator.system(), "支付回调")).isTrue();

        assertThat(statusOf(parentId)).isEqualTo(OrderStatus.PAID);
        assertThat(statusOf(childId)).isEqualTo(OrderStatus.PAID);
        assertThat(logsOf(parentId)).hasSize(1); // 父单由本事务推进 → 流水齐全
        assertThat(logsOf(childId)).isEmpty();  // 子单由并发方推进 → 流水由并发方写，不重复写
        assertThat(events).hasSize(1);
    }

    @Test
    void CAS败方_重读仍非目标态_整体回滚父单保持原状态() {
        long childId = seedParentWithChild(OrderStatus.UNPAID, OrderStatus.UNPAID);
        long parentId = parentIdOf(childId);
        List<Object> events = captureEvents(DomainEventNames.ORDER_STATUS_CHANGED);

        // 子单 CAS 命中 0 行且行仍在待付款 → 状态冲突，必须整体回滚（不允许父单半成功）
        doReturn(0).when(repo).casUpdateStatus(eq(childId), anyInt(), anyInt(), anyInt(), any());

        assertThatThrownBy(() -> stateMachine.transition(parentId, OrderStatus.PAID, Operator.system(), "支付回调"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR));

        assertThat(statusOf(parentId)).isEqualTo(OrderStatus.UNPAID);
        assertThat(statusOf(childId)).isEqualTo(OrderStatus.UNPAID);
        assertThat(logsOf(parentId)).isEmpty();
        assertThat(events).isEmpty();
    }

    @Test
    void CAS假成功_组内一致性断言拦截_整体回滚不留半成功() {
        long childId = seedParentWithChild(OrderStatus.UNPAID, OrderStatus.UNPAID);
        long parentId = parentIdOf(childId);
        List<Object> events = captureEvents(DomainEventNames.ORDER_STATUS_CHANGED);

        // 最坏情况：子单 CAS「假成功」（返回 1 但行状态没变）→ 只能靠末尾组内一致性断言兜底
        doReturn(1).when(repo).casUpdateStatus(eq(childId), anyInt(), anyInt(), anyInt(), any());

        assertThatThrownBy(() -> stateMachine.transition(parentId, OrderStatus.PAID, Operator.system(), "支付回调"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR));

        assertThat(statusOf(parentId)).isEqualTo(OrderStatus.UNPAID);
        assertThat(statusOf(childId)).isEqualTo(OrderStatus.UNPAID);
        assertThat(logsOf(parentId)).isEmpty();
        assertThat(logsOf(childId)).isEmpty(); // 假成功写入的子单流水也随事务回滚
        assertThat(events).isEmpty();
    }
}