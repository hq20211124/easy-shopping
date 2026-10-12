package com.price;

import com.easyshopping.common.event.DomainEventNames;
import com.easyshopping.common.event.InMemoryDomainEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 价格查询/写价 API + 变更事件测试：
 * GET /api/v1/price/skus 契约元素 {skuId,price,marketPrice}、管理端改价鉴权（ROLE_ADMIN）、
 * 价格变更事件 product.updated(changeType=price)。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-price-api-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class PriceApiAndEventTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InMemoryDomainEventPublisher eventPublisher;

    private final List<Map<String, Object>> priceEvents = new CopyOnWriteArrayList<>();

    @BeforeEach
    void subscribe() {
        priceEvents.clear();
        eventPublisher.subscribe(DomainEventNames.PRODUCT_UPDATED, e -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) e.data();
            if ("price".equals(data.get("changeType"))) {
                priceEvents.add(data);
            }
        });
    }

    @Test
    void adminUpsertThenPublicQueryAndEventFired() throws Exception {
        // 1. 管理端改价（ROLE_ADMIN + 幂等键）
        mockMvc.perform(put("/api/admin/price/skus/9001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "1").header("X-User-Roles", "ROLE_ADMIN")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .content(objectMapper.writeValueAsString(
                                Map.of("price", "88.88", "marketPrice", "99.99"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.price").value(88.88));

        // 2. 无管理员角色 → 403
        mockMvc.perform(put("/api/admin/price/skus/9002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "2")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .content(objectMapper.writeValueAsString(Map.of("price", "1.00"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1004));

        // 3. 公共查询契约：[{skuId, price, marketPrice}]
        MvcResult queried = mockMvc.perform(get("/api/v1/price/skus").param("ids", "9001,9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].skuId").value(9001))
                .andExpect(jsonPath("$.data[0].price").value(88.88))
                .andExpect(jsonPath("$.data[0].marketPrice").value(99.99))
                .andReturn();
        // 不存在的 skuId(9999) 不返回
        assertThat(objectMapper.readTree(queried.getResponse().getContentAsString()).get("data").size())
                .isEqualTo(1);

        // 4. 价格变更事件已随事务提交发布
        assertThat(priceEvents).hasSize(1);
        assertThat(priceEvents.get(0).get("skuIds")).isEqualTo(List.of(9001L));
    }
}
