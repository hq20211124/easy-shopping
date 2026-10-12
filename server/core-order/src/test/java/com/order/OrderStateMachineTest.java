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
 *
 * <p>本类同时固化 P0-A（终态校验必须先于幂等短路）与父子漂移自愈/整体回滚的边界。</p>
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

    /** 建一个挂在父单下的子单（可指定与父单不一致的状态，用于构造父子漂移）。 */
    private long seedChild(long parentId, OrderStatus status) {
        OrderMain parent = orderMainRepository.findById(parentId).orElseThrow();
        long id = idGenerator.nextId();
        OrderMain child = OrderMain.child(id, "202610120001" + String.format("%010d", id % 10_000_000_000L),
                parent, 30001L, parent.getOrderNo());
        child.setTotalAmount(new BigDecimal("40.00"));
        child.setPayAmount(new BigDecimal("40.00"));
        child.setOrderStatus(status.getValue());
        orderMainRepository.save(child);
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

    // -------------------------------------------------------------- P0-A 终态校验先于幂等短路

    @Test
    void 终态拦截_已关闭订单同状态重复请求_抛4002而非幂等成功() {
        long id = seedOrder(OrderStatus.CLOSED);

        // 修复前：from==target 先短路 return false → 终态订单的重复请求被静默判成功
        assertThatThrownBy(() -> stateMachine.transition(id, OrderStatus.CLOSED, Operator.system(), "重复关单"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR));

        assertThat(statusOf(id)).isEqualTo(OrderStatus.CLOSED);
        assertThat(logsOf(id)).isEmpty();
    }

    @Test
    void 终态拦截_已取消订单同状态重复请求_抛4002而非幂等成功() {
        long id = seedOrder(OrderStatus.CANCELLED);

        assertThatThrownBy(() -> stateMachine.transition(id, OrderStatus.CANCELLED, Operator.user(10086L), "重复取消"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR));

        assertThat(statusOf(id)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(logsOf(id)).isEmpty();
    }

    // -------------------------------------------------------------- P0-A 父子漂移自愈（不再直接 return false）

    @Test
    void 父子漂移自愈_父单已在目标态_补齐落后子单并写流水() {
        long parentId = seedOrder(OrderStatus.PAID);
        long childId = seedChild(parentId, OrderStatus.UNPAID); // 漂移：子单没跟上（历史数据/人工改库）

        // 修复前：父单 from==target 直接 return false，子单永远停在待付款
        assertThat(stateMachine.transition(parentId, OrderStatus.PAID, Operator.system(), "支付回调重放")).isTrue();

        assertThat(statusOf(parentId)).isEqualTo(OrderStatus.PAID);
        assertThat(statusOf(childId)).isEqualTo(OrderStatus.PAID); // 子单被补齐
        assertThat(logsOf(parentId)).isEmpty();                     // 父单状态未变，不重复写流水
        var childLogs = logsOf(childId);
        assertThat(childLogs).hasSize(1);
        assertThat(childLogs.get(0).getFromStatus()).isEqualTo(OrderStatus.UNPAID.getValue());
        assertThat(childLogs.get(0).getToStatus()).isEqualTo(OrderStatus.PAID.getValue());
        assertThat(childLogs.get(0).getReason()).contains("自愈");
    }

    @Test
    void 父子漂移自愈_组内一致时返回false且不写流水() {
        long parentId = seedOrder(OrderStatus.PAID);
        long childId = seedChild(parentId, OrderStatus.PAID);

        assertThat(stateMachine.transition(parentId, OrderStatus.PAID, Operator.system(), "重复回调")).isFalse();

        assertThat(statusOf(childId)).isEqualTo(OrderStatus.PAID);
        assertThat(logsOf(parentId)).isEmpty();
        assertThat(logsOf(childId)).isEmpty();
    }

    @Test
    void 父子漂移自愈_子单无法合法补齐_抛4002暴露数据问题() {
        long parentId = seedOrder(OrderStatus.CANCELLED);
        long childId = seedChild(parentId, OrderStatus.REFUNDED); // 已退款 → 不能被改写成已取消

        assertThatThrownBy(() -> stateMachine.transition(parentId, OrderStatus.CANCELLED, Operator.system(), "重复取消"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR));

        assertThat(statusOf(parentId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(statusOf(childId)).isEqualTo(OrderStatus.REFUNDED); // 不被自愈悄悄改写
        assertThat(logsOf(childId)).isEmpty();
    }

    // -------------------------------------------------------------- P0-B 禁止「半成功」

    @Test
    void 父子漂移_父单CAS成功后子单不可流转_整体回滚不留半成功() {
        long parentId = seedOrder(OrderStatus.UNPAID);
        long childId = seedChild(parentId, OrderStatus.CANCELLED); // 子单终态 → UNPAID→PAID 不允许
        List<Object> events = captureEvents(DomainEventNames.ORDER_STATUS_CHANGED);

        assertThatThrownBy(() -> stateMachine.transition(parentId, OrderStatus.PAID, Operator.system(), "支付回调"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR));

        assertThat(statusOf(parentId)).isEqualTo(OrderStatus.UNPAID); // 父单 CAS 已执行，随事务整体回滚
        assertThat(statusOf(childId)).isEqualTo(OrderStatus.CANCELLED);
        assertThat(logsOf(parentId)).isEmpty();
        assertThat(events).isEmpty();
    }
}
