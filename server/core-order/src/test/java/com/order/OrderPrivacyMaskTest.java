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
 *
 * <p><b>落库已改为 AES-256-GCM 密文（P1 审计登记项）</b>，但脱敏口径一字未改：
 * 出参先解密、再按 12.5 脱敏；密文相关的断言在 {@code ReceiverMobileCryptoTest}。</p>
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

    /**
     * 脱敏只发生在出参，实体侧看到的是<b>解密后</b>的明文。
     *
     * <p><b>用例意图在 P1 加密落地后做了等价改写</b>：原名「脱敏只发生在出参_落库仍为明文供P1加密链路改造」，
     * 断言本身（实体取到 {@code 13800000000}）一字未动，只是「落库仍为明文」这个前提已不成立——
     * 落库改为 AES-256-GCM 密文，加解密发生在 {@code ReceiverMobileConverter}（持久化边界），
     * 故实体内存里仍是明文。改为断言「实体读到的是解密后明文」，意图与原用例完全一致：
     * <b>脱敏发生在出参，不发生在领域对象上</b>。</p>
     *
     * <p>「列里确实是密文」由 {@code ReceiverMobileCryptoTest#下单落库为密文_列内不含明文} 用
     * JDBC 直读列断言（本类无 JdbcTemplate，不在此重复）。</p>
     */
    @Test
    void 脱敏只发生在出参_实体侧读到的是解密后明文() {
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