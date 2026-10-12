package com.order;

import com.easyshopping.common.event.DomainEventNames;
import com.order.domain.OrderStatus;
import com.order.dto.OrderDtos.CreateOrderResponse;
import com.order.dto.OrderDtos.OrderItemRequest;
import com.order.dto.OrderDtos.PaidResponse;
import com.order.service.OrderLifecycleService.CloseOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 关单 vs 支付回调竞态测试（测试要求⑥：version CAS 裁决，先到者胜）。
 *
 * <p>契约（android-backend 4.2.4 / admin-backend 4.4.4）：</p>
 * <ul>
 *   <li>关单先赢 → 迟到的成功回调不得回写为已支付，触发原路退款（REFUND_TRIGGERED）；</li>
 *   <li>回调先赢 → 超时扫描重读发现非待付款，静默跳过、不释放库存；</li>
 *   <li>真并发 → 恰好一方成功，败方按业务语义补偿。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-order-race-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.order.timeout-scan-ms=3600000",
        "app.order.timeout-scan-initial-ms=3600000",
        "app.order.pay-timeout-minutes=30"
})
class OrderRaceTest extends AbstractOrderTest {

    private CreateOrderResponse createOne() {
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L)),
                Map.of(20001L, new BigDecimal("100.00")));
        return createService.create(10086L, request(List.of(new OrderItemRequest(20001L, 1)), null));
    }

    private void expire(Long orderId) {
        var order = orderMainRepository.findById(orderId).orElseThrow();
        order.setPayDeadline(Instant.now().minusSeconds(5));
        orderMainRepository.saveAndFlush(order);
    }

    private OrderStatus statusOf(Long orderId) {
        return OrderStatus.of(orderMainRepository.findById(orderId).orElseThrow().getOrderStatus());
    }

    // -------------------------------------------------------------- 确定性裁决（两种交错各测一遍）

    @Test
    void 关单先赢_迟到支付回调不回写已支付_触发原路退款() {
        CreateOrderResponse order = createOne();
        expire(order.orderId());
        List<Object> cancelEvents = captureEvents(DomainEventNames.ORDER_CANCELLED);
        List<Object> refundEvents = captureEvents(DomainEventNames.ORDER_REFUND_APPLIED);

        // 关单胜出
        assertThat(timeoutScheduler.scanOnce()).isEqualTo(1);
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.CANCELLED);

        // 迟到的成功回调：CAS 裁决为败方 → 退款补偿，不得回写为已支付
        PaidResponse late = lifecycleService.markPaid(order.orderId(), 1);
        assertThat(late.outcome()).isEqualTo("REFUND_TRIGGERED");
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.CANCELLED);
        verify(paymentClient, times(1)).refund(eq(order.orderId()), any(BigDecimal.class), anyString());
        assertThat(refundEvents).hasSize(1);
        // 库存只释放一次（回调败方不释放也不预占）
        verify(inventoryClient, times(1)).release(order.orderId());
        assertThat(cancelEvents).hasSize(1);
    }

    @Test
    void 支付回调先赢_超时扫描静默跳过_不释放库存() {
        CreateOrderResponse order = createOne();

        // 回调胜出
        PaidResponse paid = lifecycleService.markPaid(order.orderId(), 1);
        assertThat(paid.outcome()).isEqualTo("PAID");

        // 迟到的超时扫描：重读发现非待付款 → 跳过
        expire(order.orderId());
        List<Object> cancelEvents = captureEvents(DomainEventNames.ORDER_CANCELLED);
        assertThat(timeoutScheduler.scanOnce()).isZero();
        assertThat(CloseOutcome.SKIPPED).isEqualTo(lifecycleService.closeTimedOut(order.orderId()));
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.PAID);
        verify(inventoryClient, never()).release(anyLong());
        assertThat(cancelEvents).isEmpty();
        verify(paymentClient, never()).refund(anyLong(), any(), anyString());
    }

    // -------------------------------------------------------------- 真并发裁决

    @Test
    void 并发关单与支付回调_CAS裁决恰好一方成功() throws Exception {
        CreateOrderResponse order = createOne();
        expire(order.orderId());
        List<Object> cancelEvents = captureEvents(DomainEventNames.ORDER_CANCELLED);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<CloseOutcome> closeOutcome = new AtomicReference<>();
        AtomicReference<Throwable> closeError = new AtomicReference<>();
        AtomicReference<String> paidOutcome = new AtomicReference<>();
        AtomicReference<Throwable> paidError = new AtomicReference<>();

        Thread closer = new Thread(() -> {
            ready.countDown();
            awaitQuietly(go);
            try {
                closeOutcome.set(timeoutScheduler.scanOnce() > 0
                        ? CloseOutcome.CLOSED : CloseOutcome.SKIPPED);
            } catch (Throwable t) {
                closeError.set(t);
            }
        }, "timeout-closer");
        Thread payer = new Thread(() -> {
            ready.countDown();
            awaitQuietly(go);
            try {
                paidOutcome.set(lifecycleService.markPaid(order.orderId(), 1).outcome());
            } catch (Throwable t) {
                paidError.set(t);
            }
        }, "payment-callback");

        closer.start();
        payer.start();
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        closer.join(15_000);
        payer.join(15_000);

        // 恰好一方赢（以最终状态裁决）
        OrderStatus finalStatus = statusOf(order.orderId());
        if (finalStatus == OrderStatus.CANCELLED) {
            // 关单赢：库存释放一次，迟到回调走退款补偿（或在并发冲突中被拒，由确定性用例兜底）
            verify(inventoryClient, times(1)).release(order.orderId());
            String outcome = paidOutcome.get();
            assertThat(outcome == null || "REFUND_TRIGGERED".equals(outcome)).isTrue();
            if ("REFUND_TRIGGERED".equals(outcome)) {
                verify(paymentClient, times(1)).refund(eq(order.orderId()), any(BigDecimal.class), anyString());
            }
            assertThat(cancelEvents).hasSize(1);
        } else if (finalStatus == OrderStatus.PAID) {
            // 回调赢：保持待发货，关单败方跳过（或并发冲突异常，由确定性用例兜底）
            assertThat(paidOutcome.get()).isEqualTo("PAID");
            verify(inventoryClient, never()).release(anyLong());
            boolean closeSkipped = closeOutcome.get() == CloseOutcome.SKIPPED;
            assertThat(closeSkipped || closeError.get() != null).isTrue();
        } else {
            throw new AssertionError("终态必须是已取消或待发货之一，实际: " + finalStatus
                    + " closeOutcome=" + closeOutcome.get() + " closeError=" + closeError.get()
                    + " paidOutcome=" + paidOutcome.get() + " paidError=" + paidError.get());
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
