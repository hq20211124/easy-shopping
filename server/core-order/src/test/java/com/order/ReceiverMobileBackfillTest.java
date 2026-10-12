package com.order;

import com.order.crypto.ReceiverMobileBackfillRunner;
import com.order.crypto.ReceiverMobileBackfillService;
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

/**
 * 存量明文手机号刷数测试（审计 P1 落地配套：明文/密文共存期的一次性迁移）。
 *
 * <p>要点：① <b>幂等</b>——重跑第二次转换 0 行；② <b>不碰</b>已加密行与空值行（无 PII）；
 * ③ 切换成密文后<b>读取链路无感</b>（脱敏/明文接口结果与刷数前一致）。</p>
 *
 * <p>刷数触发器 {@code ReceiverMobileBackfillRunner} 由开关
 * {@code app.crypto.receiver-mobile.backfill-enabled} 控制（默认 false），
 * 本类直接调用 {@link ReceiverMobileBackfillService} 以便断言幂等与开关语义。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-order-mobile-backfill-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.order.timeout-scan-ms=3600000",
        "app.order.timeout-scan-initial-ms=3600000",
        "app.crypto.receiver-mobile.enabled=true",
        "app.crypto.receiver-mobile.keyring=k1=ZGV2LW9yZGVyLW1vYmlsZS1rZXktMzJieXRlLTAwMDE=",
        // 开关默认关闭：这里显式打开只为验证启动期触发器存在且可装配（行为断言在下面直接调用 Service）
        "app.crypto.receiver-mobile.backfill-enabled=false"
})
class ReceiverMobileBackfillTest extends AbstractOrderTest {

    @Autowired
    private ReceiverMobileBackfillService backfillService;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private CreateOrderResponse createOne(long skuId) {
        stubCatalog(
                Map.of(skuId, sku(skuId, 10001L, 10001L)),
                Map.of(skuId, new BigDecimal("100.00")));
        return createService.create(10086L, request(List.of(new OrderItemRequest(skuId, 1)), null));
    }

    private String rawMobile(String orderNo) {
        return jdbcTemplate.queryForObject(
                "SELECT receiver_mobile FROM order_main WHERE order_no = ?", String.class, orderNo);
    }

    private void forceRawMobile(String orderNo, String value) {
        jdbcTemplate.update("UPDATE order_main SET receiver_mobile = ? WHERE order_no = ?", value, orderNo);
    }

    @Test
    void 刷数_只转存量明文_已密文与空值行原样不动() {
        CreateOrderResponse legacy = createOne(20001L);
        CreateOrderResponse alreadyCipher = createOne(20002L);
        CreateOrderResponse blank = createOne(20003L);
        forceRawMobile(legacy.orderNo(), "18612345678");
        forceRawMobile(blank.orderNo(), "");
        String cipherBefore = rawMobile(alreadyCipher.orderNo());
        assertThat(cipherBefore).startsWith("v1:");

        // batchSize=1：顺带验证 keyset 分页在多次翻页下不漏行
        ReceiverMobileBackfillService.BackfillResult result = backfillService.backfill(1);

        assertThat(result.converted()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(rawMobile(legacy.orderNo())).startsWith("v1:").doesNotContain("18612345678");
        assertThat(rawMobile(alreadyCipher.orderNo())).isEqualTo(cipherBefore);
        assertThat(rawMobile(blank.orderNo())).isEmpty();
        assertThat(orderMainRepository.findById(legacy.orderId()).orElseThrow().getReceiverMobile())
                .isEqualTo("18612345678");
    }

    @Test
    void 刷数_幂等_重复执行转换0行() {
        CreateOrderResponse legacy = createOne(20001L);
        forceRawMobile(legacy.orderNo(), "18612345678");

        assertThat(backfillService.backfill(500).converted()).isEqualTo(1);
        String afterFirst = rawMobile(legacy.orderNo());

        assertThat(backfillService.backfill(500).converted()).isZero();
        assertThat(rawMobile(legacy.orderNo())).isEqualTo(afterFirst);
    }

    @Test
    void 刷数后_出参脱敏与明文接口口径不变() {
        CreateOrderResponse legacy = createOne(20001L);
        forceRawMobile(legacy.orderNo(), "18612345678");

        assertThat(queryService.buyerDetail(legacy.orderId(), 10086L).receiverMobile()).isEqualTo("186****5678");

        backfillService.backfill(500);

        assertThat(queryService.buyerDetail(legacy.orderId(), 10086L).receiverMobile()).isEqualTo("186****5678");
        assertThat(queryService.adminDetail(legacy.orderId()).receiverMobile()).isEqualTo("186****5678");
        assertThat(queryService.receiverMobileForShip(legacy.orderNo(), 9001L).receiverMobile())
                .isEqualTo("18612345678");
    }

    @Test
    void 开关默认关闭_启动期不装配Runner() {
        // 刷数是一次性运维动作：默认开关 false ⇒ Runner 根本不被装配（而不是「装配了但空跑」）
        assertThat(context.getBeanNamesForType(ReceiverMobileBackfillRunner.class)).isEmpty();

        CreateOrderResponse legacy = createOne(20001L);
        forceRawMobile(legacy.orderNo(), "18612345678");

        // 顺带确认：没人偷偷在启动/请求路径上把明文刷掉，行保持明文直到运维显式开关
        assertThat(rawMobile(legacy.orderNo())).isEqualTo("18612345678");
    }
}