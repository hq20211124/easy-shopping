package com.order;

import com.easyshopping.common.event.DomainEventNames;
import com.order.client.RestPaymentClient;
import com.order.domain.OrderMain;
import com.order.domain.OrderStatus;
import com.order.dto.OrderDtos.CreateOrderResponse;
import com.order.dto.OrderDtos.OrderItemRequest;
import com.order.dto.OrderDtos.PaidResponse;
import com.order.service.OrderLifecycleService;
import com.order.service.OrderStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 关单补偿回归测试（审计 P0-2 / P0-3）。
 *
 * <p>① <b>退款真实性</b>：关闭已支付订单 → 真实 core-payment 退款接口被调用（mock RestClient 断言
 * URI 与请求体）；退款未被受理 → <b>不发</b> {@code order.refund.applied}（此前无条件发，
 * 对外宣告「退款已应用」而实际一分钱没退）。</p>
 *
 * <p>② <b>关单退券</b>：下单用券 → 超时关单 / 买家取消 / 后台关闭 → 券退回（此前三条路径只释放库存、
 * 从不退券，用户未付款即永久损失一张券）。无券订单安全跳过，退券失败不回滚关单。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-order-compensate-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.order.timeout-scan-ms=3600000",
        "app.order.timeout-scan-initial-ms=3600000",
        "app.order.pay-timeout-minutes=30"
})
class OrderCompensationTest extends AbstractOrderTest {

    private static final long COUPON_ID = 60001L;
    private static final long USER_ID = 10086L;

    @Autowired
    private OrderStateMachine orderStateMachine;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void refundDefaults() {
        when(paymentClient.refund(any(), any(), anyString())).thenReturn(true);
        when(promotionClient.releaseCoupon(any(), any(), any())).thenReturn(true);
    }

    private CreateOrderResponse createWithCoupon() {
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L)),
                Map.of(20001L, new BigDecimal("100.00")));
        when(promotionClient.deductCoupon(any(), any(), any(), any())).thenReturn(new BigDecimal("10.00"));
        CreateOrderResponse response = createService.create(USER_ID,
                request(List.of(new OrderItemRequest(20001L, 1)), COUPON_ID));
        // 依赖 OrderCreateService 落 coupon_id；该行尚未落地时在此补齐（补齐后本用例仍成立）
        attachCouponIdIfAbsent(response.orderId(), COUPON_ID);
        return response;
    }

    private void attachCouponIdIfAbsent(Long orderId, Long couponId) {
        OrderMain order = orderMainRepository.findById(orderId).orElseThrow();
        if (order.getCouponId() == null) {
            order.setCouponId(couponId);
            orderMainRepository.saveAndFlush(order);
        }
    }

    private void expire(Long orderId) {
        OrderMain order = orderMainRepository.findById(orderId).orElseThrow();
        order.setPayDeadline(Instant.now().minusSeconds(5));
        orderMainRepository.saveAndFlush(order);
    }

    private OrderStatus statusOf(Long orderId) {
        return OrderStatus.of(orderMainRepository.findById(orderId).orElseThrow().getOrderStatus());
    }

    // ============================== P0-2 退款真实性 ==============================

    @Test
    void 后台关闭已支付订单_调用真实退款接口_断言路径与请求体() {
        CreateOrderResponse order = createWithCoupon();
        lifecycleService.markPaid(order.orderId(), 1); // 1 待发货

        RestClient.Builder builder = RestClient.builder().baseUrl("http://core-payment");
        MockRestServiceServer refundServer = MockRestServiceServer.bindTo(builder).build();
        refundServer.expect(once(), requestTo("http://core-payment/api/v1/payment/refund"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.orderId").value(order.orderId()))
                .andExpect(jsonPath("$.reason").value("后台关闭已支付订单，原路退款"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"status\":\"refunded\",\"refundAmount\":90.00}}",
                        MediaType.APPLICATION_JSON));
        OrderLifecycleService realLifecycle = new OrderLifecycleService(orderMainRepository, orderStateMachine,
                inventoryClient, promotionClient, new RestPaymentClient(builder.build()), eventPublisher,
                transactionManager);

        List<Object> refundEvents = captureEvents(DomainEventNames.ORDER_REFUND_APPLIED);
        realLifecycle.closeByAdmin(order.orderId(), 9L, "缺货");

        refundServer.verify(); // 真实退款被调用，且路径/请求体符合 core-payment 契约
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.CLOSED);
        assertThat(refundEvents).as("退款已被受理才允许发 order.refund.applied").hasSize(1);
    }

    @Test
    void 迟到支付回调_真实退款被调用() {
        CreateOrderResponse order = createWithCoupon();
        expire(order.orderId());
        assertThat(timeoutScheduler.scanOnce()).isEqualTo(1); // 关单胜出

        RestClient.Builder builder = RestClient.builder().baseUrl("http://core-payment");
        MockRestServiceServer refundServer = MockRestServiceServer.bindTo(builder).build();
        refundServer.expect(once(), requestTo("http://core-payment/api/v1/payment/refund"))
                .andExpect(jsonPath("$.orderId").value(order.orderId()))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"status\":\"refunded\"}}", MediaType.APPLICATION_JSON));
        OrderLifecycleService realLifecycle = new OrderLifecycleService(orderMainRepository, orderStateMachine,
                inventoryClient, promotionClient, new RestPaymentClient(builder.build()), eventPublisher,
                transactionManager);

        List<Object> refundEvents = captureEvents(DomainEventNames.ORDER_REFUND_APPLIED);
        PaidResponse late = realLifecycle.markPaid(order.orderId(), 1);

        refundServer.verify();
        assertThat(late.outcome()).isEqualTo("REFUND_TRIGGERED");
        assertThat(refundEvents).hasSize(1);
    }

    @Test
    void 退款未被受理_不发refund事件_关单仍然完成() {
        CreateOrderResponse order = createWithCoupon();
        lifecycleService.markPaid(order.orderId(), 1);
        when(paymentClient.refund(any(), any(), anyString())).thenReturn(false); // 支付域拒绝/不可用

        List<Object> refundEvents = captureEvents(DomainEventNames.ORDER_REFUND_APPLIED);
        lifecycleService.closeByAdmin(order.orderId(), 9L, "缺货");

        verify(paymentClient, times(1)).refund(eq(order.orderId()), any(BigDecimal.class), anyString());
        assertThat(refundEvents).as("退款未受理时不得对外宣告退款已应用").isEmpty();
        // 退款失败不回滚关单（资金事实已不可逆，由对账兜底）
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.CLOSED);
    }

    @Test
    void 退款调用抛异常_不发refund事件() {
        CreateOrderResponse order = createWithCoupon();
        expire(order.orderId());
        assertThat(timeoutScheduler.scanOnce()).isEqualTo(1);

        when(paymentClient.refund(any(), any(), anyString()))
                .thenThrow(new IllegalStateException("core-payment 不可达"));
        List<Object> refundEvents = captureEvents(DomainEventNames.ORDER_REFUND_APPLIED);

        lifecycleService.markPaid(order.orderId(), 1);

        assertThat(refundEvents).as("退款通道异常时不得发成功事件").isEmpty();
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.CANCELLED);
    }

    // ============================== P0-3 关单退券 ==============================

    @Test
    void 下单用券_超时关单_券退回() {
        CreateOrderResponse order = createWithCoupon();
        expire(order.orderId());

        assertThat(timeoutScheduler.scanOnce()).isEqualTo(1);

        verify(promotionClient, times(1)).releaseCoupon(eq(COUPON_ID), eq(USER_ID), eq(order.orderId()));
        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 下单用券_买家取消_券退回() {
        CreateOrderResponse order = createWithCoupon();

        lifecycleService.cancelByUser(order.orderId(), USER_ID, "不想要了");

        verify(promotionClient, times(1)).releaseCoupon(eq(COUPON_ID), eq(USER_ID), eq(order.orderId()));
    }

    @Test
    void 下单用券_后台关闭未支付_券退回() {
        CreateOrderResponse order = createWithCoupon();

        lifecycleService.closeByAdmin(order.orderId(), 9L, "商家缺货");

        verify(promotionClient, times(1)).releaseCoupon(eq(COUPON_ID), eq(USER_ID), eq(order.orderId()));
    }

    @Test
    void 无券订单_关单跳过退券_不产生空调用() {
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L)),
                Map.of(20001L, new BigDecimal("100.00")));
        CreateOrderResponse order = createService.create(USER_ID,
                request(List.of(new OrderItemRequest(20001L, 1)), null));
        expire(order.orderId());

        assertThat(timeoutScheduler.scanOnce()).isEqualTo(1);

        verify(promotionClient, never()).releaseCoupon(anyLong(), any(), any());
    }

    @Test
    void 退券失败_不回滚关单_订单仍为已取消() {
        CreateOrderResponse order = createWithCoupon();
        expire(order.orderId());
        when(promotionClient.releaseCoupon(any(), any(), any())).thenReturn(false);

        assertThat(timeoutScheduler.scanOnce()).isEqualTo(1); // 退券失败不得卡住关单

        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.CANCELLED);
        verify(inventoryClient, times(1)).release(order.orderId());
    }

    @Test
    void 退券调用抛异常_不回滚关单() {
        CreateOrderResponse order = createWithCoupon();
        expire(order.orderId());
        when(promotionClient.releaseCoupon(any(), any(), any()))
                .thenThrow(new IllegalStateException("core-promotion 不可达"));

        assertThat(timeoutScheduler.scanOnce()).isEqualTo(1);

        assertThat(statusOf(order.orderId())).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void 支付回调胜出_不关单不退券() {
        CreateOrderResponse order = createWithCoupon();
        lifecycleService.markPaid(order.orderId(), 1);
        expire(order.orderId());

        assertThat(timeoutScheduler.scanOnce()).isZero(); // 已支付，跳过

        verify(promotionClient, never()).releaseCoupon(anyLong(), any(), any());
        verify(inventoryClient, never()).release(anyLong());
    }
}