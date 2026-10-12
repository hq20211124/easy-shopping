package com.order;

import com.easyshopping.common.api.UserContext;
import com.order.domain.OrderMain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 幂等键去重测试（测试要求④：Idempotency-Key 防重复下单）。
 *
 * <p>写接口 {@code POST /api/v1/orders} 挂 {@code @Idempotent}（common 的 IdempotentInterceptor，
 * P0 内存 SETNX 占位）：同一 Idempotency-Key 重复提交 → 第二次 1005/409 冲突，
 * 不会重复建单；缺失幂等键 → 1001 参数错误。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-order-idem-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.order.timeout-scan-ms=3600000",
        "app.order.timeout-scan-initial-ms=3600000"
})
@AutoConfigureMockMvc
class OrderIdempotencyTest extends AbstractOrderTest {

    @Autowired
    private MockMvc mockMvc;

    /** 固定下单请求体（同 SKU 单行 1 件）。 */
    private static final String BODY = """
            {"items":[{"skuId":20001,"quantity":1}],"addressId":10,
             "receiverName":"张三","receiverMobile":"13800000000",
             "receiverAddress":"北京市朝阳区望京SOHO T1","remark":"幂等测试","source":1}
            """;

    private MockHttpServletRequestBuilder create(String idempotencyKey) {
        MockHttpServletRequestBuilder builder = post("/api/v1/orders")
                .header(UserContext.HEADER_USER_ID, "10086")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return builder;
    }

    private long parentCount() {
        return orderMainRepository.findAll().stream().filter(OrderMain::isParent).count();
    }

    @Test
    void 场景6_相同幂等键重复提交_只建一笔订单() throws Exception {
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L)),
                Map.of(20001L, new BigDecimal("59.90")));

        // 首次提交：200 + 订单落库（父单 + 1 子单）
        mockMvc.perform(create("idem-key-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.orderNo").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value(0));
        assertThat(parentCount()).isEqualTo(1);
        assertThat(orderMainRepository.count()).isEqualTo(2);

        // 同键重复提交：1005 幂等冲突（HTTP 409），不重复建单
        mockMvc.perform(create("idem-key-001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1005));
        assertThat(parentCount()).isEqualTo(1);
        assertThat(orderMainRepository.count()).isEqualTo(2);

        // 换键再次提交：新订单（证明去重键就是 Idempotency-Key 本身）
        mockMvc.perform(create("idem-key-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(parentCount()).isEqualTo(2);
    }

    @Test
    void 写接口缺失幂等键_拒绝下单() throws Exception {
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L)),
                Map.of(20001L, new BigDecimal("59.90")));

        mockMvc.perform(create(null))
                .andExpect(jsonPath("$.code").value(1001)); // PARAM_ERROR：缺少幂等键
        assertThat(parentCount()).isZero();
    }

    @Test
    void 幂等键作用域_按方法加URI隔离_不跨接口撞键() throws Exception {
        stubCatalog(
                Map.of(20001L, sku(20001L, 10001L, 10001L)),
                Map.of(20001L, new BigDecimal("59.90")));

        // 同一 key 走别名 URI（/api/v1/order/create）：scopedKey = METHOD:URI:key 不同 → 放行
        mockMvc.perform(post("/api/v1/order/create")
                        .header(UserContext.HEADER_USER_ID, "10086")
                        .header("Idempotency-Key", "idem-key-scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk());
        assertThat(parentCount()).isEqualTo(1);
    }
}
