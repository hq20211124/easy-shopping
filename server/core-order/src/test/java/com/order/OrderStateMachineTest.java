package com.order;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.order.domain.OrderMain;
import com.order.domain.OrderStatus;
import com.order.domain.OrderStatusLog.Operator;
import com.order.repo.OrderMainRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机测试（测试要求①）：8 值 0 基全流转 + 非法流转拒绝 + 终态拦截 + 父子级联 + 幂等。
 *
 * <p>白名单唯一来源：plans/admin-backend.md 4.3 / 4.4.1（0待付款→1待发货→2待收货→3已完成，
 * 1/2/3→4退款中→5已退款→7已关闭，0→6已取消，6/7 终态）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-order-stm-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.order.timeout-scan-ms=3600000",
        "app.order.timeout-scan-initial-ms=3600000"
})
class OrderStateMachineTest extends AbstractOrderTest {

    @Autowired
    private SnowflakeIdGenerator idGenerator;

    private long seedOrder(OrderStatus status) {
        long id = idGenerator.nextId();
        OrderMain order = OrderMain.parent(id, "202610120000" + String.format("%04d%06d", 1, id % 1_000_000), 10086L);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setPayAmount(new BigDecimal("100.00"));
        order.setPayDeadline(Instant.now().plusSeconds(1800));
        order.setOrderStatus(status.getValue());
        orderMainRepository.save(order);
        return id;
    }

    private OrderStatus statusOf(long orderId) {
        return OrderStatus.of(orderMainRepository.findById(orderId).orElseThrow().getOrderStatus());
    }

    // -------------------------------------------------------------- 全流转

    @Test
    void 全流转_待付款到已完成_主路径() {
        long id = seedOrder(OrderStatus.UNPAID);
        assertThat(stateMachine.transition(id, OrderStatus.PAID, Operator.system(), "支付回调")).isTrue();
        assertThat(stateMachine.transition(id, OrderStatus.SHIPPED, Operator.admin(1L), "发货")).isTrue();
        assertThat(stateMachine.transition(id, OrderStatus.COMPLETED, Operator.user(10086L), "确认收货")).isTrue();
        assertThat(statusOf(id)).isEqualTo(OrderStatus.COMPLETED);
        // 流水：3 条（建单流水不在状态机内，seedOrder 未写）
        var logs = logsOf(id);
        assertThat(logs).hasSize(3);
        assertThat(logs.get(0).getFromStatus()).isEqualTo(OrderStatus.UNPAID.getValue());
        assertThat(logs.get(0).getToStatus()).isEqualTo(OrderStatus.PAID.getValue());
        assertThat(logs.get(2).getToStatus()).isEqualTo(OrderStatus.COMPLETED.getValue());
    }

    @Test
    void 全流转_待付款到已取消() {
        long id = seedOrder(OrderStatus.UNPAID);
        assertThat(stateMachine.transition(id, OrderStatus.CANCELLED, Operator.system(), "超时未支付")).isTrue();
        assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 全流转_已完成到已退款到已关闭() {
        long id = seedOrder(OrderStatus.COMPLETED);
        assertThat(stateMachine.transition(id, OrderStatus.REFUNDING, Operator.user(10086L), "申请售后")).isTrue();
        assertThat(stateMachine.transition(id, OrderStatus.REFUNDED, Operator.system(), "退款成功")).isTrue();
        // 5→7 归档边仅系统任务可触发
        assertThat(stateMachine.transition(id, OrderStatus.CLOSED, Operator.system(), "财务对账归档")).isTrue();
        assertThat(statusOf(id)).isEqualTo(OrderStatus.CLOSED);
    }

    @Test
    void 全流转_退款失败恢复原状态() {
        long id = seedOrder(OrderStatus.PAID);
        assertThat(stateMachine.transition(id, OrderStatus.REFUNDING, Operator.user(10086L), "申请退款")).isTrue();
        assertThat(stateMachine.transition(id, OrderStatus.COMPLETED, Operator.system(), "退款失败恢复")).isTrue();
        assertThat(statusOf(id)).isEqualTo(OrderStatus.COMPLETED);
    }

    // -------------------------------------------------------------- 非法流转 / 终态

    @Test
    void 非法流转_白名单外_拒绝并保持原状态() {
        long id = seedOrder(OrderStatus.UNPAID);
        assertThatThrownBy(() -> stateMachine.transition(id, OrderStatus.SHIPPED, Operator.system(), "跳级发货"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR));
        assertThat(statusOf(id)).isEqualTo(OrderStatus.UNPAID);
        assertThat(logsOf(id)).isEmpty();
    }

    @Test
    void 终态拦截_已取消订单禁止任何流转() {
        long id = seedOrder(OrderStatus.CANCELLED);
        for (OrderStatus target : List.of(OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.COMPLETED,
                OrderStatus.REFUNDING, OrderStatus.REFUNDED)) {
            assertThatThrownBy(() -> stateMachine.transition(id, target, Operator.system(), "终态试探"))
                    .isInstanceOfSatisfying(BizException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR));
        }
        assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 终态拦截_已关闭订单禁止任何流转() {
        long id = seedOrder(OrderStatus.CLOSED);
        assertThatThrownBy(() -> stateMachine.transition(id, OrderStatus.PAID, Operator.system(), "终态试探"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR));
    }

    @Test
    void 归档边受限_人工禁止5到7() {
        long id = seedOrder(OrderStatus.REFUNDED);
        assertThatThrownBy(() -> stateMachine.transition(id, OrderStatus.CLOSED, Operator.admin(1L), "人工归档"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR));
        assertThat(statusOf(id)).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void 订单不存在_4001() {
        assertThatThrownBy(() -> stateMachine.transition(999_999_999L, OrderStatus.PAID, Operator.system(), "x"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_NOT_FOUND));
    }

    // -------------------------------------------------------------- 幂等 / 级联

    @Test
    void 幂等_已在目标状态返回false不重复记流水() {
        long id = seedOrder(OrderStatus.PAID);
        assertThat(stateMachine.transition(id, OrderStatus.PAID, Operator.system(), "重复回调")).isFalse();
        assertThat(logsOf(id)).isEmpty();
    }

    @Test
    void 父子级联_父单流转子单跟随且各记流水() {
        long parentId = seedOrder(OrderStatus.UNPAID);
        OrderMain parent = orderMainRepository.findById(parentId).orElseThrow();
        long childId = idGenerator.nextId();
        OrderMain child = OrderMain.child(childId, "202610120001000000001", parent, 30001L, parent.getOrderNo());
        child.setTotalAmount(new BigDecimal("60.00"));
        child.setPayAmount(new BigDecimal("60.00"));
        orderMainRepository.save(child);

        assertThat(stateMachine.transition(parentId, OrderStatus.PAID, Operator.system(), "支付回调")).isTrue();

        assertThat(statusOf(parentId)).isEqualTo(OrderStatus.PAID);
        assertThat(statusOf(childId)).isEqualTo(OrderStatus.PAID);
        assertThat(logsOf(parentId)).hasSize(1);
        assertThat(logsOf(childId)).hasSize(1);
    }

    @Test
    void 子单操作归一到父单() {
        long parentId = seedOrder(OrderStatus.UNPAID);
        OrderMain parent = orderMainRepository.findById(parentId).orElseThrow();
        long childId = idGenerator.nextId();
        OrderMain child = OrderMain.child(childId, "202610120001000000002", parent, 30001L, parent.getOrderNo());
        child.setTotalAmount(new BigDecimal("40.00"));
        child.setPayAmount(new BigDecimal("40.00"));
        orderMainRepository.save(child);

        // 对子单调用 == 对父单流转并级联
        assertThat(stateMachine.transition(childId, OrderStatus.CANCELLED, Operator.system(), "超时")).isTrue();
        assertThat(statusOf(parentId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(statusOf(childId)).isEqualTo(OrderStatus.CANCELLED);
    }
}
