package com.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端/买家端 API 冒烟测试（MockMvc + mem H2）：
 * 类目 CRUD、商品创建（幂等键 + 管理员角色头）、审核通过自动上架、买家详情含 SKU、
 * 内部批量查询契约、cost:read 字段级脱敏。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-product-api-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.product.price-client.enabled=false"
})
@AutoConfigureMockMvc
class ProductApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void categoryProductAndInternalApiFlow() throws Exception {
        // 1. 创建类目（写接口必须带幂等键 —— DEV.md 4.2 步骤 5）
        long categoryId = createCategory("数码");

        // 2. 管理员创建商品（P0：创建即草稿）
        Map<String, Object> product = Map.of(
                "name", "测试手机", "subtitle", "A17 芯片", "categoryId", categoryId,
                "mainImage", "https://cdn.example.com/main.jpg", "detail", "<p>hi</p>",
                "skus", List.of(
                Map.of("spec", Map.of("颜色", "黑", "容量", "256GB"), "price", "7999.00",
                        "marketPrice", "8999.00", "costPrice", "7000.00"),
                Map.of("spec", Map.of("颜色", "白", "容量", "512GB"), "price", "8999.00",
                        "costPrice", "8000.00")));
        MvcResult created = mockMvc.perform(post("/api/admin/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "1").header("X-User-Roles", "ROLE_ADMIN")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .content(objectMapper.writeValueAsString(product)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.auditStatus").value(0)) // draft
                .andExpect(jsonPath("$.data.minPrice").value(7999.00))
                .andReturn();
        JsonNode createdNode = objectMapper.readTree(created.getResponse().getContentAsString());
        long spuId = createdNode.at("/data/id").asLong();

        // 3. 无 ROLE_ADMIN 角色调管理端接口 → 403（1004）
        mockMvc.perform(post("/api/admin/product/" + spuId + "/submit-audit")
                        .header("X-User-Id", "2")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1004));

        // 4. 提交审核 + 审核通过（自动上架）
        mockMvc.perform(post("/api/admin/product/" + spuId + "/submit-audit")
                        .header("X-User-Id", "1").header("X-User-Roles", "ROLE_ADMIN")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auditStatus").value(1));
        mockMvc.perform(post("/api/admin/product/" + spuId + "/approve")
                        .header("X-User-Id", "1").header("X-User-Roles", "ROLE_ADMIN")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auditStatus").value(2))
                .andExpect(jsonPath("$.data.shelfStatus").value(1));

        // 5. 买家详情：SKU 列表 + 规格聚合（价格客户端关闭时 SKU price 为 null，SPU 冗余价兜底）
        MvcResult detail = mockMvc.perform(get("/api/v1/products/" + spuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skus.length()").value(2))
                .andExpect(jsonPath("$.data.specs.length()").value(2)) // 颜色 / 容量 两组
                .andExpect(jsonPath("$.data.price").value(7999.00))
                .andReturn();
        JsonNode detailNode = objectMapper.readTree(detail.getResponse().getContentAsString());
        long skuId = detailNode.at("/data/skus/0/skuId").asLong();

        // 6. 买家列表按类目筛选
        mockMvc.perform(get("/api/v1/products").param("categoryId", String.valueOf(categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].spuId").value(spuId))
                .andExpect(jsonPath("$.data.list[0].skuCount").value(2));

        // 7. 内部批量 SKU 契约：[{skuId,spuId,title,spec,images,status}]
        mockMvc.perform(get("/api/v1/internal/skus").param("ids", String.valueOf(skuId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].skuId").value(spuId > 0 ? skuId : 0))
                .andExpect(jsonPath("$.data[0].spuId").value(spuId))
                .andExpect(jsonPath("$.data[0].spec", containsString("颜色")))
                .andExpect(jsonPath("$.data[0].status").value(1));

        // 8. 内部冗余同步视图（core-search 拉取源）
        MvcResult internal = mockMvc.perform(get("/api/v1/internal/products")).andReturn();
        JsonNode products = objectMapper.readTree(internal.getResponse().getContentAsString());
        assertThat(products.at("/data/list").size()).isEqualTo(1);
        assertThat(products.at("/data/list/0/spuId").asLong()).isEqualTo(spuId);

        // 9. cost:read 字段级权限：无该角色 costPrice 脱敏为 null，有则返回（plans/admin-backend.md 2.2.3）
        mockMvc.perform(get("/api/admin/product/" + spuId)
                        .header("X-User-Id", "1").header("X-User-Roles", "ROLE_ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skus[0].costPrice").doesNotExist());
        mockMvc.perform(get("/api/admin/product/" + spuId)
                        .header("X-User-Id", "1").header("X-User-Roles", "ROLE_ADMIN,cost:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skus[0].costPrice").value(7000.00));
    }

    private long createCategory(String name) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/admin/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "1").header("X-User-Roles", "ROLE_ADMIN")
                        .header("Idempotency-Key", UUID.randomUUID())
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).at("/data/id").asLong();
    }
}
