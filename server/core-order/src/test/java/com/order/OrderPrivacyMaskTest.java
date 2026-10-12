package com.order;

import com.easyshopping.common.api.PageResult;
import com.order.dto.OrderDtos.AdminOrderVO;
import com.order.dto.OrderDtos.CreateOrderResponse;
import com.order.dto.OrderDtos.OrderItemRequest;
import com.order.service.OrderQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 收货人手机号脱敏测试（审计 P0-D）。
 *
 * <p>依据 plans/ios-backend.md 1.2.1 注释：「order_main.receiver_mobile 等收货信息同为个人敏感信息，
 * 接口输出统一按 12.5 规则脱敏」；12.6 隐私原则 2「接口返回遵循最小化原则」；
 * admin-backend 4.4.3「敏感列脱敏后再写文件」。plans 未对订单 owner 开明文例外，
 * 故买家端详情同样按脱敏处理（保守口径）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-order-mask-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.order.timeout-scan-ms=3600000",
        "app.order.timeout-scan-initial-ms=3600000"
})
class OrderPrivacyMaskTest extends AbstractOrderTest {

    /** 收货人手机号取自 AbstractOrderTest.request()（13800000000），脱敏后为 138****0000。 */
    private static final String MOBILE = "13800000000";
    private static final String MASKED = "138****0000";

    private CreateOrderResponse createOne() {
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L)),
                Map.of(20001L, new BigDecimal("100.00")));
        return createService.create(10086L, request(List.of(new OrderItemRequest(20001L, 1)), null));
    }

    @Test
    void 买家端详情_收货人手机号脱敏() {
        CreateOrderResponse order = createOne();

        assertThat(queryService.buyerDetail(order.orderId(), 10086L).receiverMobile())
                .isEqualTo(MASKED);
    }

    @Test
    void 管理端列表_收货人手机号脱敏() {
        CreateOrderResponse order = createOne();

        PageResult<AdminOrderVO> page = queryService.adminList(null, null, null, null, false, 1, 10);

        assertThat(page.list()).isNotEmpty();
        assertThat(page.list()).allSatisfy(vo -> assertThat(vo.receiverMobile()).isEqualTo(MASKED));
    }

    @Test
    void 管理端详情_收货人手机号脱敏() {
        CreateOrderResponse order = createOne();

        assertThat(queryService.adminDetail(order.orderId()).receiverMobile()).isEqualTo(MASKED);
    }

    @Test
    void 脱敏只发生在出参_落库仍为明文供P1加密链路改造() {
        CreateOrderResponse order = createOne();

        assertThat(orderMainRepository.findById(order.orderId()).orElseThrow().getReceiverMobile())
                .isEqualTo(MOBILE);
    }

    @Test
    void 脱敏规则_保留前3后4_空值与超短号码按约定处理() {
        assertThat(OrderQueryService.maskMobile("13800001234")).isEqualTo("138****1234");
        assertThat(OrderQueryService.maskMobile("1380000")).isEqualTo("138****0000"); // 7 位：前3+后4 恰好全覆盖
        assertThat(OrderQueryService.maskMobile("12345")).isEqualTo("****");         // <7 位整体打码
        assertThat(OrderQueryService.maskMobile("")).isEqualTo("");                   // 订单表默认空串
        assertThat(OrderQueryService.maskMobile(null)).isNull();
    }
}