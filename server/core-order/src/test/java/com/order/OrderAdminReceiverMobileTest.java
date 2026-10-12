package com.order;

import com.easyshopping.common.api.UserContext;
import com.easyshopping.common.event.DomainEventNames;
import com.order.domain.OrderStatus;
import com.order.domain.OrderStatusLog;
import com.order.dto.OrderDtos.CreateOrderResponse;
import com.order.dto.OrderDtos.OrderItemRequest;
import com.order.web.AdminOrderController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端「收货人明文手机号」专用接口测试（审计 P1 遗留项：脱敏后发货/联系快递拿不到明文）。
 *
 * <p>接口：{@code GET /api/admin/orders/{orderNo}/receiver-mobile}，
 * 三重闸门 = 管理员角色 + 专门权限码 {@code order:mobile:read}（fail-closed）+ 操作人身份非空；
 * 通过后返回<b>本单明文</b>并写一条操作留痕流水（order_status_log，operator=管理端）。</p>
 *
 * <p>产品口径提醒：「订单 owner 本人可见明文」仍属待确认（AGENTS.md），
 * 因此本类同时钉住<b>买家端/管理端列表详情继续脱敏</b>，明文只从这一个口子出去。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-order-receiver-mobile-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.order.timeout-scan-ms=3600000",
        "app.order.timeout-scan-initial-ms=3600000"
})
@AutoConfigureMockMvc
class OrderAdminReceiverMobileTest extends AbstractOrderTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String PLAINTEXT = "13800000000"; // AbstractOrderTest.request() 的收货人手机号
    private static final String MASKED = "138****0000";
    private static final long ADMIN_ID = 9001L;

    private CreateOrderResponse createOne() {
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L)),
                Map.of(20001L, new BigDecimal("100.00")));
        return createService.create(10086L, request(List.of(new OrderItemRequest(20001L, 1)), null));
    }

    private List<OrderStatusLog> viewLogs(Long orderId) {
        return logsOf(orderId).stream()
                .filter(log -> OrderStatusLog.REASON_VIEW_RECEIVER_MOBILE.equals(log.getReason()))
                .toList();
    }

    // ------------------------------------------------------------------ 正例：有权 + 留痕

    @Test
    void 有权限码_返回本单明文并写操作留痕() throws Exception {
        CreateOrderResponse order = createOne();

        mockMvc.perform(get("/api/admin/orders/{orderNo}/receiver-mobile", order.orderNo())
                        .header(UserContext.HEADER_USER_ID, String.valueOf(ADMIN_ID))
                        .header(UserContext.HEADER_USER_ROLES, "ADMIN")
                        .header(AdminOrderController.HEADER_ADMIN_PERMS,
                                "order:detail," + AdminOrderController.PERM_RECEIVER_MOBILE_READ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.orderNo").value(order.orderNo()))
                .andExpect(jsonPath("$.data.receiverMobile").value(PLAINTEXT));

        // 留痕五要素：订单号 / 操作人 / 时间 / 事由 / 订单 ID，且 from==to（非状态流转）
        List<OrderStatusLog> traces = viewLogs(order.orderId());
        assertThat(traces).hasSize(1);
        OrderStatusLog trace = traces.get(0);
        assertThat(trace.getOrderNo()).isEqualTo(order.orderNo());
        assertThat(trace.getOrderId()).isEqualTo(order.orderId());
        assertThat(trace.getOperatorId()).isEqualTo(ADMIN_ID);
        assertThat(trace.getOperatorType()).isEqualTo(2); // 2-商家/管理端（ios-backend 1.2.1 DDL）
        assertThat(trace.getReason()).isEqualTo(OrderStatusLog.REASON_VIEW_RECEIVER_MOBILE);
        assertThat(trace.getFromStatus()).isEqualTo(trace.getToStatus())
                .isEqualTo(OrderStatus.UNPAID.getValue());
        assertThat(trace.getCreatedAt()).isNotNull();
    }

    @Test
    void 返回体只有本单明文_无任何批量或列表结构() throws Exception {
        CreateOrderResponse order = createOne();

        String body = mockMvc.perform(get("/api/admin/order/{orderNo}/receiver-mobile", order.orderNo())
                        .header(UserContext.HEADER_USER_ID, String.valueOf(ADMIN_ID))
                        .header(UserContext.HEADER_USER_ROLES, "ADMIN")
                        .header(AdminOrderController.HEADER_ADMIN_PERMS,
                                AdminOrderController.PERM_RECEIVER_MOBILE_READ))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"list\"").doesNotContain("\"total\"")
                .doesNotContain("138****0000");
    }

    @Test
    void 每查一次留一条痕_重复查看可累计() throws Exception {
        CreateOrderResponse order = createOne();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/admin/orders/{orderNo}/receiver-mobile", order.orderNo())
                    .header(UserContext.HEADER_USER_ID, String.valueOf(ADMIN_ID))
                    .header(UserContext.HEADER_USER_ROLES, "ADMIN")
                    .header(AdminOrderController.HEADER_ADMIN_PERMS,
                            AdminOrderController.PERM_RECEIVER_MOBILE_READ))
                    .andExpect(status().isOk());
        }

        assertThat(viewLogs(order.orderId())).hasSize(2);
    }

    // ------------------------------------------------------------------ 反例：无权限 / 无留痕

    @Test
    void 无权限码头_403且不写留痕() throws Exception {
        CreateOrderResponse order = createOne();

        mockMvc.perform(get("/api/admin/orders/{orderNo}/receiver-mobile", order.orderNo())
                        .header(UserContext.HEADER_USER_ID, String.valueOf(ADMIN_ID))
                        .header(UserContext.HEADER_USER_ROLES, "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1004));

        assertThat(viewLogs(order.orderId())).isEmpty();
    }

    @Test
    void 有权限码但不含本码_403且不写留痕() throws Exception {
        CreateOrderResponse order = createOne();

        mockMvc.perform(get("/api/admin/orders/{orderNo}/receiver-mobile", order.orderNo())
                        .header(UserContext.HEADER_USER_ID, String.valueOf(ADMIN_ID))
                        .header(UserContext.HEADER_USER_ROLES, "ADMIN")
                        .header(AdminOrderController.HEADER_ADMIN_PERMS, "order:detail,order:ship"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1004))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("order:mobile:read")));

        assertThat(viewLogs(order.orderId())).isEmpty();
    }

    @Test
    void 空白权限码头_403且不写留痕() throws Exception {
        CreateOrderResponse order = createOne();

        mockMvc.perform(get("/api/admin/orders/{orderNo}/receiver-mobile", order.orderNo())
                        .header(UserContext.HEADER_USER_ID, String.valueOf(ADMIN_ID))
                        .header(UserContext.HEADER_USER_ROLES, "ADMIN")
                        .header(AdminOrderController.HEADER_ADMIN_PERMS, "  "))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1004));

        assertThat(viewLogs(order.orderId())).isEmpty();
    }

    @Test
    void 买家角色_403且不写留痕() throws Exception {
        CreateOrderResponse order = createOne();

        mockMvc.perform(get("/api/admin/orders/{orderNo}/receiver-mobile", order.orderNo())
                        .header(UserContext.HEADER_USER_ID, "10086")
                        .header(UserContext.HEADER_USER_ROLES, "USER")
                        .header(AdminOrderController.HEADER_ADMIN_PERMS,
                                AdminOrderController.PERM_RECEIVER_MOBILE_READ))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1004));

        assertThat(viewLogs(order.orderId())).isEmpty();
    }

    @Test
    void 有权限码但无操作人身份_401且不写留痕() throws Exception {
        CreateOrderResponse order = createOne();

        mockMvc.perform(get("/api/admin/orders/{orderNo}/receiver-mobile", order.orderNo())
                        .header(UserContext.HEADER_USER_ROLES, "ADMIN")
                        .header(AdminOrderController.HEADER_ADMIN_PERMS,
                                AdminOrderController.PERM_RECEIVER_MOBILE_READ))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1002));

        assertThat(viewLogs(order.orderId())).isEmpty();
    }

    @Test
    void 订单不存在_4001且不写留痕() throws Exception {
        mockMvc.perform(get("/api/admin/orders/{orderNo}/receiver-mobile", "202610129999999999999999")
                        .header(UserContext.HEADER_USER_ID, String.valueOf(ADMIN_ID))
                        .header(UserContext.HEADER_USER_ROLES, "ADMIN")
                        .header(AdminOrderController.HEADER_ADMIN_PERMS,
                                AdminOrderController.PERM_RECEIVER_MOBILE_READ))
                .andExpect(jsonPath("$.code").value(4001));

        assertThat(orderStatusLogRepository.findAll())
                .noneMatch(log -> OrderStatusLog.REASON_VIEW_RECEIVER_MOBILE.equals(log.getReason()));
    }

    // ------------------------------------------------------------------ 回归：其它出参仍脱敏

    @Test
    void 回归_买家端与管理端列表详情仍脱敏_明文只从专用接口出() throws Exception {
        CreateOrderResponse order = createOne();

        assertThat(queryService.buyerDetail(order.orderId(), 10086L).receiverMobile()).isEqualTo(MASKED);
        assertThat(queryService.adminDetail(order.orderId()).receiverMobile()).isEqualTo(MASKED);

        String detail = mockMvc.perform(get("/api/admin/order/{id}", order.orderId())
                        .header(UserContext.HEADER_USER_ID, String.valueOf(ADMIN_ID))
                        .header(UserContext.HEADER_USER_ROLES, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.receiverMobile").value(MASKED))
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain(PLAINTEXT);
    }

    @Test
    void 回归_查看留痕不改订单状态_不产生状态流转事件() throws Exception {
        CreateOrderResponse order = createOne();
        List<Object> events = captureEvents(DomainEventNames.ORDER_STATUS_CHANGED);

        mockMvc.perform(get("/api/admin/orders/{orderNo}/receiver-mobile", order.orderNo())
                        .header(UserContext.HEADER_USER_ID, String.valueOf(ADMIN_ID))
                        .header(UserContext.HEADER_USER_ROLES, "ADMIN")
                        .header(AdminOrderController.HEADER_ADMIN_PERMS,
                                AdminOrderController.PERM_RECEIVER_MOBILE_READ))
                .andExpect(status().isOk());

        assertThat(queryService.adminDetail(order.orderId()).status()).isEqualTo(OrderStatus.UNPAID.getValue());
        // 真实状态流转只有「建单」一条（from=-1→0）；查看明文只追加 from==to 的留痕，不进状态机
        List<OrderStatusLog> transitions = logsOf(order.orderId()).stream()
                .filter(log -> log.getFromStatus() != log.getToStatus())
                .toList();
        assertThat(transitions).hasSize(1);
        assertThat(transitions.get(0).getReason()).isEqualTo("订单创建");
        assertThat(events).isEmpty();
    }
}