package com.order;

import com.easyshopping.common.api.BizException;
import com.order.crypto.ReceiverMobileBackfillRunner;
import com.order.crypto.ReceiverMobileBackfillService;
import com.order.crypto.ReceiverMobileCipher;
import com.order.dto.OrderDtos.CreateOrderResponse;
import com.order.dto.OrderDtos.OrderItemRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回滚开关（{@code app.crypto.receiver-mobile.enabled=false}）语义测试。
 *
 * <p><b>开关语义</b>：关闭后<b>只停写不停读</b>——</p>
 * <ol>
 *   <li>新写入退回明文（应急通道：密文链路出故障时优先恢复下单）；</li>
 *   <li>读取仍按格式解密历史密文行（否则管理端/买家端会把 {@code v1:...} 当手机号渲染出去——
 *       那是比明文更糟的事故）；</li>
 *   <li>刷数无意义（加密退化为恒等映射），Service 直接跳过并告警。</li>
 * </ol>
 *
 * <p>生产建议<b>常开</b>，关闭只在应急时临时使用并事后回滚。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-order-mobile-disabled-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.order.timeout-scan-ms=3600000",
        "app.order.timeout-scan-initial-ms=3600000",
        "app.crypto.receiver-mobile.enabled=false",
        // 关闭加密时 keyring 仍保留：这样「历史密文行」才解得开（关停写不停读的前提）
        "app.crypto.receiver-mobile.keyring=k1=ZGV2LW9yZGVyLW1vYmlsZS1rZXktMzJieXRlLTAwMDE=",
        "app.crypto.receiver-mobile.backfill-enabled=true"
})
class ReceiverMobileDisabledTest extends AbstractOrderTest {

    private static final String MOBILE = "13800000000";
    private static final String MASKED = "138****0000";

    @Autowired
    private ReceiverMobileCipher cipher;
    @Autowired
    private ReceiverMobileBackfillService backfillService;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 收尾：清掉故意写入的历史密文行，否则 deleteAll 读取时会解密失败。 */
    @org.junit.jupiter.api.AfterEach
    void neutraliseCipherRows() {
        jdbcTemplate.update("UPDATE order_main SET receiver_mobile = ''");
    }

    private CreateOrderResponse createOne() {
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L)),
                Map.of(20001L, new BigDecimal("100.00")));
        return createService.create(10086L, request(List.of(new OrderItemRequest(20001L, 1)), null));
    }

    private String rawMobile(String orderNo) {
        return jdbcTemplate.queryForObject(
                "SELECT receiver_mobile FROM order_main WHERE order_no = ?", String.class, orderNo);
    }

    @Test
    void 开关关闭_新写入退回明文_出参口径不变() {
        CreateOrderResponse order = createOne();

        assertThat(rawMobile(order.orderNo())).isEqualTo(MOBILE);
        assertThat(queryService.buyerDetail(order.orderId(), 10086L).receiverMobile()).isEqualTo(MASKED);
        assertThat(queryService.adminDetail(order.orderId()).receiverMobile()).isEqualTo(MASKED);
        assertThat(queryService.receiverMobileForShip(order.orderNo(), 9001L).receiverMobile())
                .isEqualTo(MOBILE);
        assertThat(cipher.isEncryptionEnabled()).isFalse();
    }

    @Test
    void 开关关闭且带keyring_读取仍兼容解密历史密文行() {
        // 这是「应急关闭加密」的真实场景：开关关了但 keyring 还在，历史密文行必须照常读出来，
        // 否则管理端/买家端会看到 "v1:k1:xxx" 这种被当成手机号的垃圾串
        String historical = new ReceiverMobileCipher(true,
                "k1=ZGV2LW9yZGVyLW1vYmlsZS1rZXktMzJieXRlLTAwMDE=", "").encrypt(MOBILE);
        CreateOrderResponse order = createOne();
        jdbcTemplate.update("UPDATE order_main SET receiver_mobile = ? WHERE order_no = ?",
                historical, order.orderNo());

        assertThat(queryService.adminDetail(order.orderId()).receiverMobile()).isEqualTo(MASKED);
        assertThat(queryService.receiverMobileForShip(order.orderNo(), 9001L).receiverMobile())
                .isEqualTo(MOBILE);
    }

    @Test
    void 开关关闭且无keyring_历史密文行failClosed_不返回垃圾串() {
        // keyId 不在 keyring 里（例如密钥被误删/被误摘）⇒ 必须明确报错，不返回 v1: 垃圾串
        String historical = new ReceiverMobileCipher(true,
                "k-legacy=ZGV2LW9yZGVyLW1vYmlsZS1rZXktMzJieXRlLTAwMDE=", "").encrypt(MOBILE);
        CreateOrderResponse order = createOne();
        jdbcTemplate.update("UPDATE order_main SET receiver_mobile = ? WHERE order_no = ?",
                historical, order.orderNo());

        assertThatThrownBy(() -> queryService.adminDetail(order.orderId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("k-legacy");
    }

    @Test
    void 开关关闭_刷数跳过_无意义告警不误刷() {
        CreateOrderResponse order = createOne();
        jdbcTemplate.update("UPDATE order_main SET receiver_mobile = ? WHERE order_no = ?",
                "18612345678", order.orderNo());

        assertThat(backfillService.backfill(500).converted()).isZero();
        assertThat(rawMobile(order.orderNo())).isEqualTo("18612345678");
    }

    @Test
    void 开关与刷数开关独立_刷数Runner仍被装配() {
        // backfill-enabled=true 与加密 enabled=false 是两个开关，互不影响装配
        assertThat(context.getBeanNamesForType(ReceiverMobileBackfillRunner.class)).isNotEmpty();
    }
}