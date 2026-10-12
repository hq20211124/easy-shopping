package com.order;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.event.DomainEventNames;
import com.order.domain.OrderMain;
import com.order.domain.OrderStatus;
import com.order.dto.OrderDtos.CreateOrderResponse;
import com.order.dto.OrderDtos.OrderItemRequest;
import com.order.dto.OrderDtos.PaidResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 订单生命周期测试：支付回调（幂等/入账回填）、买家取消、确认收货（联动出库）、
 * 后台发货、后台关闭（已支付退款处置）、越权防护。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-order-lifecycle-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.order.timeout-scan-ms=3600000",
        "app.order.timeout-scan-initial-ms=3600000"
})
class OrderLifecycleTest extends AbstractOrderTest {

    private CreateOrderResponse createOne() {
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L)),
                Map.of(20001L, new BigDecimal("100.00")));
        return createService.create(10086L, request(List.of(new OrderItemRequest(20001L, 1)), null));
    }

    private OrderMain reload(Long orderId) {
        return orderMainRepository.findById(orderId).orElseThrow();
    }

    @Test
    void 支付回调_入账并回填支付信息_事件在提交后() {
        CreateOrderResponse order = createOne();
        List<Object> paidEvents = captureEvents(DomainEventNames.ORDER_PAID);

        PaidResponse result = lifecycleService.markPaid(order.orderId(), 2);

        assertThat(result.outcome()).isEqualTo("PAID");
        assertThat(result.status()).isEqualTo(OrderStatus.PAID.getValue());
        OrderMain parent = reload(order.orderId());
        assertThat(OrderStatus.of(parent.getOrderStatus())).isEqualTo(OrderStatus.PAID);
        assertThat(parent.getPayType()).isEqualTo(2);
        assertThat(parent.getPayTime()).isNotNull();
        assertThat(paidEvents).hasSize(1);
    }

    @Test
    void 支付回调_重复回调幂等() {
        CreateOrderResponse order = createOne();
        lifecycleService.markPaid(order.orderId(), 1);
        PaidResponse replay = lifecycleService.markPaid(order.orderId(), 1);

        assertThat(replay.outcome()).isEqualTo("ALREADY_PAID");
        // 建单 -1→0 + 首次回调 0→1 各一条；重复回调不新增流水
        assertThat(logsOf(order.orderId())).hasSize(2);
    }

    @Test
    void 买家取消_待付款_释放库存并发事件() {
        CreateOrderResponse order = createOne();
        List<Object> cancelEvents = captureEvents(DomainEventNames.ORDER_CANCELLED);

        lifecycleService.cancelByUser(order.orderId(), 10086L, "不想要了");

        OrderMain parent = reload(order.orderId());
        assertThat(OrderStatus.of(parent.getOrderStatus())).isEqualTo(OrderStatus.CANCELLED);
        assertThat(parent.getCloseReason()).isEqualTo("manual");
        verify(inventoryClient, times(1)).release(eq(order.orderId()));
        assertThat(cancelEvents).hasSize(1);
    }

    @Test
    void 买家取消_已付款拒绝() {
        CreateOrderResponse order = createOne();
        lifecycleService.markPaid(order.orderId(), 1);

        assertThatThrownBy(() -> lifecycleService.cancelByUser(order.orderId(), 10086L, "x"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR));
        verify(inventoryClient, never()).release(anyLong());
    }

    @Test
    void 越权防护_他人订单按不存在处理() {
        CreateOrderResponse order = createOne();
        assertThatThrownBy(() -> lifecycleService.cancelByUser(order.orderId(), 654321L, "x"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    void 确认收货_待收货到已完成_联动库存出库() {
        CreateOrderResponse order = createOne();
        lifecycleService.markPaid(order.orderId(), 1);
        lifecycleService.ship(order.orderId(), 9L, "SF123456789");

        lifecycleService.confirmReceive(order.orderId(), 10086L);

        OrderMain parent = reload(order.orderId());
        assertThat(OrderStatus.of(parent.getOrderStatus())).isEqualTo(OrderStatus.COMPLETED);
        assertThat(parent.getDeliveryTime()).isNotNull();
        assertThat(parent.getReceiveTime()).isNotNull();
        verify(inventoryClient, times(1)).deduct(eq(order.orderId()));
    }

    @Test
    void 后台发货_幂等重复发货为无操作() {
        CreateOrderResponse order = createOne();
        lifecycleService.markPaid(order.orderId(), 1);

        lifecycleService.ship(order.orderId(), 9L, "SF1");
        lifecycleService.ship(order.orderId(), 9L, "SF1"); // 重复发货：状态机幂等空操作

        assertThat(OrderStatus.of(reload(order.orderId()).getOrderStatus())).isEqualTo(OrderStatus.SHIPPED);
        // 建单 -1→0、0→1、1→2 各一条，重复发货不新增
        assertThat(logsOf(order.orderId())).hasSize(3);
    }

    @Test
    void 后台关闭_已支付未发货_释放库存并退款处置() {
        CreateOrderResponse order = createOne();
        lifecycleService.markPaid(order.orderId(), 1);

        lifecycleService.closeByAdmin(order.orderId(), 9L, "缺货");

        OrderMain parent = reload(order.orderId());
        assertThat(OrderStatus.of(parent.getOrderStatus())).isEqualTo(OrderStatus.CLOSED);
        assertThat(parent.getCloseReason()).isEqualTo("manual");
        verify(inventoryClient, times(1)).release(eq(order.orderId()));
        verify(paymentClient, times(1)).refund(eq(order.orderId()), any(BigDecimal.class), eq("后台关闭已支付订单，原路退款"));
    }

    @Test
    void 后台关闭_已发货拒绝_需先走售后() {
        CreateOrderResponse order = createOne();
        lifecycleService.markPaid(order.orderId(), 1);
        lifecycleService.ship(order.orderId(), 9L, "SF1");

        assertThatThrownBy(() -> lifecycleService.closeByAdmin(order.orderId(), 9L, "x"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR));
    }

    @Test
    void 确认收货_重复确认幂等_不重复扣减() {
        CreateOrderResponse order = createOne();
        lifecycleService.markPaid(order.orderId(), 1);
        lifecycleService.ship(order.orderId(), 9L, "SF1");
        lifecycleService.confirmReceive(order.orderId(), 10086L);

        assertThatThrownBy(() -> lifecycleService.confirmReceive(order.orderId(), 10086L))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_STATUS_ERROR));
        verify(inventoryClient, times(1)).deduct(eq(order.orderId())); // 只扣一次
    }
}
