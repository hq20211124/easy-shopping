package com.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.MaskUtil;
import com.order.dto.OrderDtos.CreateOrderResponse;
import com.order.dto.OrderDtos.InternalOrderSnapshot;
import com.order.dto.OrderDtos.OrderItemRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * internal 订单快照字段收窄测试（审计 P1 遗留项：internalOrder 原返回整实体，含<b>明文</b> receiver_mobile）。
 *
 * <p>危害面：core-payment（prepay 反查）与 core-promotion（券回收对账）直连 internal 端点，
 * 整返 {@code OrderMain} 等于把收货人明文手机号/收货地址/备注一并开放给不需要它们的调用方，
 * 而删除成本极高（已被抓包/日志/第三方留存）。</p>
 *
 * <p>本类钉住三件事：① 响应字段集<b>恰好</b>是已知消费方消费过的那 6 个（不多不少）；
 * ② 隐私字段一个都不出现；③ 字段名与 core-payment {@code RestOrderClient#toSnapshot} /
 * core-promotion {@code RestDeductedOrderVerifier} 的解析口径<b>逐字一致</b>
 * （解析逻辑在本类中复刻，契约漂移即红）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-order-internal-snapshot-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.order.timeout-scan-ms=3600000",
        "app.order.timeout-scan-initial-ms=3600000"
})
@AutoConfigureMockMvc
class OrderInternalSnapshotTest extends AbstractOrderTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** 快照允许出现的字段全集（= 已知消费方消费过的字段，一个不多）。 */
    private static final List<String> ALLOWED_FIELDS = List.of(
            "orderId", "userId", "totalAmount", "payAmount", "status", "payDeadline");

    /** 必须<b>不</b>出现的字段（收货个人信息 + 无消费方的业务字段）。 */
    private static final List<String> FORBIDDEN_FIELDS = List.of(
            "receiverMobile", "receiverName", "receiverAddress", "remark",
            "couponId", "payType", "version", "storeId", "parentOrderNo", "id", "orderNo");

    private CreateOrderResponse createWithCoupon() {
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L)),
                Map.of(20001L, new BigDecimal("100.00")));
        // 券抵扣 10 元：total=100.00 / pay=90.00，构造「总额≠应付」的真实场景（P0-B5 同款）
        when(promotionClient.deductCoupon(any(), any(), any(), any())).thenReturn(new BigDecimal("10.00"));
        return createService.create(10086L, request(List.of(new OrderItemRequest(20001L, 1)), 777L));
    }

    private JsonNode internalSnapshotData(long orderId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/internal/orders/{orderId}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    // ------------------------------------------------------------------ 字段收窄

    @Test
    void 响应字段集恰好为已知消费字段_不多不少() throws Exception {
        JsonNode data = internalSnapshotData(createWithCoupon().orderId());

        List<String> actual = new ArrayList<>();
        data.fieldNames().forEachRemaining(actual::add);

        assertThat(actual).containsExactlyInAnyOrderElementsOf(ALLOWED_FIELDS);
        assertThat(actual).doesNotContainAnyElementsOf(FORBIDDEN_FIELDS);
    }

    @Test
    void 响应不含收货人明文手机号与地址() throws Exception {
        CreateOrderResponse order = createWithCoupon();

        String body = mockMvc.perform(get("/api/v1/internal/orders/{orderId}", order.orderId()))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("13800000000");      // 明文手机号
        assertThat(body).doesNotContain("张三");              // 收货人姓名
        assertThat(body).doesNotContain("望京");              // 收货地址
        assertThat(body).doesNotContain(MaskUtil.maskMobile("13800000000"));
    }

    @Test
    void 服务层快照是DTO_序列化后同样不含明文手机号() throws Exception {
        CreateOrderResponse order = createWithCoupon();

        InternalOrderSnapshot snapshot = queryService.internalOrder(order.orderId());
        String json = objectMapper.writeValueAsString(snapshot);

        assertThat(json).doesNotContain("receiverMobile").doesNotContain("13800000000");
        assertThat(snapshot.orderId()).isEqualTo(order.orderId());
        assertThat(snapshot.userId()).isEqualTo(10086L);
        assertThat(snapshot.status()).isZero(); // 0 基状态机：待付款
    }

    // ------------------------------------------------------------------ 调用方兼容（不改坏 core-payment / core-promotion）

    @Test
    void corePayment解析口径_六个字段全部可取且金额口径正确() throws Exception {
        JsonNode data = internalSnapshotData(createWithCoupon().orderId());

        // ↓↓↓ 逐字复刻 core-payment RestOrderClient#toSnapshot（改动必须同步该类，否则这里会红）
        Long orderId = firstLong(data, "orderId", "id");
        Long userId = firstLong(data, "userId");
        BigDecimal totalAmount = firstDecimal(data, "totalAmount");
        BigDecimal payAmount = firstDecimal(data, "payAmount", "amount");
        Integer status = data.hasNonNull("status") ? data.get("status").asInt() : null;
        Instant payDeadline = firstInstant(data, "payDeadline");
        // ↑↑↑

        assertThat(orderId).isNotNull();
        assertThat(userId).isEqualTo(10086L);
        assertThat(status).isZero();
        assertThat(payDeadline).isNotNull();
        // 金额口径：定额只认 payAmount（90.00），totalAmount（100.00）仅参考——两者不等，防改价
        assertThat(payAmount).isEqualByComparingTo("90.00");
        assertThat(totalAmount).isEqualByComparingTo("100.00");
        assertThat(payAmount).isNotEqualByComparingTo(totalAmount);
    }

    @Test
    void corePromotion解析口径_status为数字可判存活() throws Exception {
        JsonNode data = internalSnapshotData(createWithCoupon().orderId());

        // core-promotion RestDeductedOrderVerifier：status 非数字 → 保守 UNKNOWN（不回收券）
        assertThat(data.path("status").isNumber()).isTrue();
        assertThat(data.path("status").asInt()).isZero(); // 非 6/7 → ORDER_ALIVE
    }

    @Test
    void 订单不存在_4001_调用方据此映射为ORDER_NOT_FOUND() throws Exception {
        mockMvc.perform(get("/api/v1/internal/orders/{orderId}", 999999L))
                .andExpect(jsonPath("$.code").value(BizErrorCode.ORDER_NOT_FOUND.getCode()));
    }

    @Test
    void 服务层订单不存在_抛4001() {
        assertThatThrownBy(() -> queryService.internalOrder(999999L))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.ORDER_NOT_FOUND));
    }

    // ------------------------------------------------------------------ core-payment 解析逻辑复刻

    private Long firstLong(JsonNode node, String... fields) {
        for (String f : fields) {
            if (node.hasNonNull(f)) {
                return node.get(f).asLong();
            }
        }
        return null;
    }

    private BigDecimal firstDecimal(JsonNode node, String... fields) {
        for (String f : fields) {
            if (node.hasNonNull(f)) {
                return new BigDecimal(node.get(f).asText());
            }
        }
        return null;
    }

    private Instant firstInstant(JsonNode node, String... fields) {
        for (String f : fields) {
            if (node.hasNonNull(f)) {
                JsonNode v = node.get(f);
                if (v.isNumber()) {
                    return Instant.ofEpochMilli(v.asLong());
                }
                try {
                    return Instant.parse(v.asText());
                } catch (Exception ignore) {
                    return null;
                }
            }
        }
        return null;
    }
}