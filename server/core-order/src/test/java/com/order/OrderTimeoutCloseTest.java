package com.order;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.event.DomainEventNames;
import com.order.domain.OrderMain;
import com.order.domain.OrderStatus;
import com.order.dto.OrderDtos.CreateOrderResponse;
import com.order.dto.OrderDtos.OrderItemRequest;
import com.order.service.OrderLifecycleService.CloseOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 支付超时关单测试（测试要求⑤：pay_deadline 超时 → 自动关单并释放库存）。
 *
 * <p>调度器每 10s 扫 {@code status=0待付款 AND pay_deadline < now} 的父单，
 * 逐单 CAS 关单（子单级联）→ 释放预占库存 → 发 order.cancelled；
 * 已支付订单不入扫描（跳过，不释放库存）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-order-timeout-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.order.timeout-scan-ms=3600000",
        "app.order.timeout-scan-initial-ms=3600000",
        "app.order.pay-timeout-minutes=30"
})
class OrderTimeoutCloseTest extends AbstractOrderTest {

    private CreateOrderResponse createOne() {
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L)),
                Map.of(20001L, new BigDecimal("100.00")));
        return createService.create(10086L, request(List.of(new OrderItemRequest(20001L, 2)), null));
    }

    /** 把 pay_deadline 推到过去（模拟超时）。 */
    private void expire(Long orderId) {
        OrderMain order = orderMainRepository.findById(orderId).orElseThrow();
        order.setPayDeadline(Instant.now().minusSeconds(5));
        orderMainRepository.saveAndFlush(order);
    }

    private OrderStatus statusOf(Long orderId) {
        return OrderStatus.of(orderMainRepository.findById(orderId).orElseThrow().getOrderStatus());
    }

    @Test
    void 超时未支付_扫描关单_释放库存并发事件() {
        CreateOrderResponse order = createOne();
        expire(order.orderId());
        List<Object> cancelEvents = captureEvents(DomainEventNames.ORDER_CANCELLED);

        int closed = timeoutScheduler.scanOnce();

        assertThat(closed).isEqualTo(1);
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.CANCELLED); // 0→6
        OrderMain parent = orderMainRepository.findById(order.orderId()).orElseThrow();
        assertThat(parent.getCloseReason()).isEqualTo("timeout");
        // 子单随父单级联
        orderMainRepository.findByParentOrderNo(parent.getOrderNo()).forEach(child ->
                assertThat(OrderStatus.of(child.getOrderStatus())).isEqualTo(OrderStatus.CANCELLED));
        // 释放预占库存（幂等键=父单 ID）
        verify(inventoryClient, times(1)).release(order.orderId());
        assertThat(cancelEvents).hasSize(1);
        // 状态流水：建单 -1→0 + 超时 0→6
        var logs = logsOf(order.orderId());
        assertThat(logs).hasSize(2);
        assertThat(logs.get(1).getFromStatus()).isEqualTo(OrderStatus.UNPAID.getValue());
        assertThat(logs.get(1).getToStatus()).isEqualTo(OrderStatus.CANCELLED.getValue());
    }

    @Test
    void 超时扫描_二次扫描幂等_不再重复关单() {
        CreateOrderResponse order = createOne();
        expire(order.orderId());

        assertThat(timeoutScheduler.scanOnce()).isEqualTo(1);
        assertThat(timeoutScheduler.scanOnce()).isZero(); // 已非待付款，不再命中
        verify(inventoryClient, times(1)).release(order.orderId()); // 只释放一次
    }

    @Test
    void 已支付订单_超时扫描跳过_不关单不释放() {
        CreateOrderResponse order = createOne();
        lifecycleService.markPaid(order.orderId(), 1);
        expire(order.orderId());

        assertThat(timeoutScheduler.scanOnce()).isZero();
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.PAID); // 保持待发货
        verify(inventoryClient, never()).release(anyLong());
    }

    @Test
    void 终态订单_再关单拒绝_不重复释放() {
        CreateOrderResponse order = createOne();
        lifecycleService.cancelByUser(order.orderId(), 10086L, "不想要了");
        verify(inventoryClient, times(1)).release(order.orderId());

        assertThatThrownBy(() -> lifecycleService.closeByAdmin(order.orderId(), 9L, "重复关单"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR));
        verify(inventoryClient, times(1)).release(order.orderId()); // 仍是 1 次
    }
}
