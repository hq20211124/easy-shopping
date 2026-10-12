package com.order;

import com.easyshopping.common.event.DomainEventNames;
import com.order.client.dto.ClientDtos.SkuView;
import com.order.domain.OrderItem;
import com.order.domain.OrderMain;
import com.order.domain.OrderStatus;
import com.order.dto.OrderDtos.CreateOrderResponse;
import com.order.dto.OrderDtos.OrderItemRequest;
import com.order.repo.OrderItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 下单拆单测试（测试要求②）：按 store_id 拆单、优惠按比例分摊、尾差归集到金额最大的子单、
 * Σ子单实付 == 父单实付（admin-backend 4.4.2 规则 5 金额校验）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-order-split-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.order.timeout-scan-ms=3600000",
        "app.order.timeout-scan-initial-ms=3600000",
        "app.order.pay-timeout-minutes=30"
})
class OrderCreateSplitTest extends AbstractOrderTest {

    @Test
    void 拆单_三店铺优惠比例分摊_尾差归集金额最大子单且总额平衡() {
        // 目录：店10001 两个 SKU（各 100 元）、店20001 一个（100 元）、店30001 一个（101 元）
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L),
                        20002L, sku(20002L, 10002L, 10001L),
                        30001L, sku(30001L, 20001L, 20001L),
                        40001L, sku(40001L, 30001L, 30001L)),
                Map.of(20001L, new BigDecimal("100.00"),
                        20002L, new BigDecimal("100.00"),
                        30001L, new BigDecimal("100.00"),
                        40001L, new BigDecimal("101.00")));
        // 优惠券 100 元（promotion 打桩）
        org.mockito.Mockito.when(promotionClient.deductCoupon(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BigDecimal("100.00"));

        List<Object> events = captureEvents(DomainEventNames.ORDER_CREATED);

        // 下单：4 个 SKU 横跨 3 家店铺，商品总额 401
        CreateOrderResponse response = createService.create(10086L, request(List.of(
                new OrderItemRequest(20001L, 1),
                new OrderItemRequest(20002L, 1),
                new OrderItemRequest(30001L, 1),
                new OrderItemRequest(40001L, 1)), 60001L));

        // ---- 父单 ----
        OrderMain parent = orderMainRepository.findById(response.orderId()).orElseThrow();
        assertThat(parent.isParent()).isTrue();
        assertThat(parent.isSplitFlag()).isTrue();
        assertThat(parent.getTotalAmount()).isEqualByComparingTo("401.00");
        assertThat(parent.getDiscountAmount()).isEqualByComparingTo("100.00");
        assertThat(parent.getPayAmount()).isEqualByComparingTo("301.00");
        // pay_deadline 默认 30 分钟（产品待确认参数）
        assertThat(response.expireSeconds()).isBetween(1700L, 1801L);

        // ---- 子单：按 storeId 升序 ----
        List<OrderMain> children = orderMainRepository.findByParentOrderNo(parent.getOrderNo()).stream()
                .sorted(Comparator.comparing(OrderMain::getStoreId))
                .toList();
        assertThat(children).hasSize(3);
        assertThat(children.get(0).getStoreId()).isEqualTo(10001L);
        assertThat(children.get(1).getStoreId()).isEqualTo(20001L);
        assertThat(children.get(2).getStoreId()).isEqualTo(30001L);

        // 比例分摊（2 位 HALF_UP）：200/401*100=49.88、100/401*100=24.94、101/401*100=25.19，
        // Σ=100.01 → 尾差 -0.01 归集到金额最大的子单（店10001，200 元）→ 49.87
        // （修复前归集到「首单」：本例首单恰好就是最大单，故数值不变；口径差异见下方不均衡用例）
        assertThat(children.get(0).getDiscountAmount()).isEqualByComparingTo("49.87");
        assertThat(children.get(1).getDiscountAmount()).isEqualByComparingTo("24.94");
        assertThat(children.get(2).getDiscountAmount()).isEqualByComparingTo("25.19");

        // Σ子单优惠 == 父单优惠；Σ子单实付 == 父单实付（规则 5）
        BigDecimal sumDiscount = children.stream().map(OrderMain::getDiscountAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumPay = children.stream().map(OrderMain::getPayAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDiscount).isEqualByComparingTo("100.00");
        assertThat(sumPay).isEqualByComparingTo(parent.getPayAmount());
        assertThat(children.get(0).getPayAmount()).isEqualByComparingTo("150.13");
        assertThat(children.get(2).getPayAmount()).isEqualByComparingTo("75.81");

        // ---- 明细归属子单，行级优惠 Σ == 子单优惠 ----
        List<OrderItem> items = orderItemRepository.findByOrderIdIn(
                children.stream().map(OrderMain::getId).toList());
        assertThat(items).hasSize(4);
        for (OrderMain child : children) {
            List<OrderItem> childItems = orderItemRepository.findByOrderNo(child.getOrderNo());
            assertThat(childItems).isNotEmpty();
            BigDecimal lineSum = childItems.stream().map(OrderItem::getDiscountAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(lineSum).isEqualByComparingTo(child.getDiscountAmount());
        }

        // ---- order_no：24 位定长（时间14+槽位4+序列6），父子不重复 ----
        List<String> orderNos = new java.util.ArrayList<>();
        orderNos.add(parent.getOrderNo());
        children.forEach(c -> orderNos.add(c.getOrderNo()));
        orderNos.forEach(no -> {
            assertThat(no).hasSize(24);
            assertThat(no).matches("\\d{24}");
        });
        assertThat(orderNos.stream().distinct().count()).isEqualTo(4);

        // ---- 预占库存：幂等键=父单雪花 ID，TTL 与 pay_deadline 对齐 ----
        verify(inventoryClient, times(1)).reserve(eq(parent.getId()),
                org.mockito.ArgumentMatchers.argThat(items2 -> items2.size() == 4), anyLong());

        // ---- order.created 事件（事务提交后）----
        assertThat(events).hasSize(1);
    }

    @Test
    void 拆单_分摊基数极不均衡_尾差归集到金额最大的子单而非首单() {
        // 店铺 ID 升序（10001 < 20001 < 30001）与金额大小完全反向：
        // 首单只有 0.01 元，中间那单 100 元是最大单（plans 4.4.2「尾差归入金额最大的子单」）
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L),
                        30001L, sku(30001L, 20001L, 20001L),
                        40001L, sku(40001L, 30001L, 30001L)),
                Map.of(20001L, new BigDecimal("0.01"),
                        30001L, new BigDecimal("100.00"),
                        40001L, new BigDecimal("0.01")));
        org.mockito.Mockito.when(promotionClient.deductCoupon(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(new BigDecimal("50.00"));

        CreateOrderResponse response = createService.create(10086L, request(List.of(
                new OrderItemRequest(20001L, 1),
                new OrderItemRequest(30001L, 1),
                new OrderItemRequest(40001L, 1)), 60002L));

        OrderMain parent = orderMainRepository.findById(response.orderId()).orElseThrow();
        List<OrderMain> children = orderMainRepository.findByParentOrderNo(parent.getOrderNo()).stream()
                .sorted(Comparator.comparing(OrderMain::getStoreId))
                .toList();
        assertThat(children).hasSize(3);
        assertThat(parent.getTotalAmount()).isEqualByComparingTo("100.02");
        assertThat(parent.getDiscountAmount()).isEqualByComparingTo("50.00");
        assertThat(parent.getPayAmount()).isEqualByComparingTo("50.02");

        // 尾差 +0.01 必须落在店20001（100 元），不能落到店10001（0.01 元）
        assertThat(children.get(0).getDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(children.get(1).getDiscountAmount()).isEqualByComparingTo("50.00");
        assertThat(children.get(2).getDiscountAmount()).isEqualByComparingTo("0.00");

        // Σ子单优惠 == 父单优惠；Σ子单实付 == 父单实付（规则 5）
        assertThat(children.stream().map(OrderMain::getDiscountAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("50.00");
        assertThat(children.stream().map(OrderMain::getPayAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(parent.getPayAmount());
    }

    @Test
    void 拆单_单店铺也生成一个子单_无优惠无尾差() {
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L)),
                Map.of(20001L, new BigDecimal("59.90")));

        CreateOrderResponse response = createService.create(10086L,
                request(List.of(new OrderItemRequest(20001L, 2)), null));

        OrderMain parent = orderMainRepository.findById(response.orderId()).orElseThrow();
        List<OrderMain> children = orderMainRepository.findByParentOrderNo(parent.getOrderNo());
        assertThat(children).hasSize(1);
        assertThat(children.get(0).getTotalAmount()).isEqualByComparingTo("119.80");
        assertThat(children.get(0).getDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(children.get(0).getPayAmount()).isEqualByComparingTo("119.80");
        assertThat(parent.getPayAmount()).isEqualByComparingTo("119.80");
        // 明细挂子单
        assertThat(orderItemRepository.findByOrderNo(children.get(0).getOrderNo())).hasSize(1);
    }

    @Test
    void 下单_库存不足_整单失败不留订单() {
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L)),
                Map.of(20001L, new BigDecimal("100.00")));
        org.mockito.Mockito.doThrow(new com.easyshopping.common.api.BizException(
                        com.easyshopping.common.api.BizErrorCode.OUT_OF_STOCK, "库存不足"))
                .when(inventoryClient).reserve(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyList(), anyLong());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> createService.create(10086L,
                        request(List.of(new OrderItemRequest(20001L, 1)), null)))
                .isInstanceOfSatisfying(com.easyshopping.common.api.BizException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(com.easyshopping.common.api.BizErrorCode.OUT_OF_STOCK));
        assertThat(orderMainRepository.count()).isZero();
        // 预占失败不需要补偿释放（未产生锁定）
        verify(inventoryClient, times(0)).release(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 下单_同SKU重复行_数量合并_状态初始待付款() {
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L)),
                Map.of(20001L, new BigDecimal("10.00")));

        CreateOrderResponse response = createService.create(10086L, request(List.of(
                new OrderItemRequest(20001L, 2),
                new OrderItemRequest(20001L, 3)), null));

        assertThat(response.status()).isEqualTo(OrderStatus.UNPAID.getValue());
        OrderMain parent = orderMainRepository.findById(response.orderId()).orElseThrow();
        List<OrderItem> items = orderItemRepository.findByOrderNo(
                orderMainRepository.findByParentOrderNo(parent.getOrderNo()).get(0).getOrderNo());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getQuantity()).isEqualTo(5); // 2+3 合并
        assertThat(parent.getTotalAmount()).isEqualByComparingTo("50.00");
        // 建单初始流水（父 + 子）
        assertThat(logsOf(parent.getId())).hasSize(1);
    }
}
