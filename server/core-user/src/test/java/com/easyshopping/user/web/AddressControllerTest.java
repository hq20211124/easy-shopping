package com.easyshopping.user.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 地址 CRUD：默认地址唯一、软删顺延、手机号脱敏（mem H2）。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-user-address-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AddressControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private long userId;

    private void ensureUser() throws Exception {
        if (userId > 0) {
            return;
        }
        String mobile = "137" + String.format("%08d", System.nanoTime() % 100_000_000L);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "reg-" + UUID.randomUUID())
                        .content("{\"mobile\":\"" + mobile + "\",\"code\":\"123456\",\"password\":\"pass-123456\"}"))
                .andExpect(status().isOk())
                .andReturn();
        userId = objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("userId").asLong();
    }

    private long createAddress(String name, String mobile, boolean isDefault) throws Exception {
        ensureUser();
        MvcResult result = mockMvc.perform(post("/api/v1/user/addresses")
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", "addr-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"mobile\":\"" + mobile + "\","
                                + "\"province\":\"北京市\",\"city\":\"北京市\",\"district\":\"海淀区\","
                                + "\"detail\":\"中关村大街 1 号\"," + "\"isDefault\":" + isDefault + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("addressId").asLong();
    }

    @Test
    void firstAddressBecomesDefaultAndOnlyOneDefaultExists() throws Exception {
        long first = createAddress("张三", "13800138000", false);
        long second = createAddress("李四", "13900139000", true);

        MvcResult list = mockMvc.perform(get("/api/v1/user/addresses").header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode addresses = objectMapper.readTree(list.getResponse().getContentAsString())
                .path("data").path("addresses");

        assertThat(addresses.size()).isEqualTo(2);
        long defaultCount = 0;
        for (JsonNode a : addresses) {
            if (a.path("isDefault").asBoolean()) {
                defaultCount++;
                assertThat(a.path("id").asLong()).isEqualTo(second); // 第二条接管默认位
            }
        }
        assertThat(defaultCount).isEqualTo(1);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void addressMobileIsMaskedInList() throws Exception {
        ensureUser();
        createAddress("王五", "13612345678", false);

        MvcResult list = mockMvc.perform(get("/api/v1/user/addresses").header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode addresses = objectMapper.readTree(list.getResponse().getContentAsString())
                .path("data").path("addresses");
        assertThat(addresses.get(0).path("mobile").asText()).isEqualTo("136****5678");
    }

    @Test
    void deleteDefaultAddressPromotesNextOne() throws Exception {
        ensureUser();
        long a = createAddress("赵六", "13511112222", true);
        createAddress("钱七", "13533334444", false);

        mockMvc.perform(delete("/api/v1/user/addresses/{id}", a)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", "del-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));

        MvcResult list = mockMvc.perform(get("/api/v1/user/addresses").header("X-User-Id", userId))
                .andReturn();
        JsonNode addresses = objectMapper.readTree(list.getResponse().getContentAsString())
                .path("data").path("addresses");
        assertThat(addresses.size()).isEqualTo(1);
        assertThat(addresses.get(0).path("isDefault").asBoolean()).isTrue(); // 软删后默认位顺延
    }

    @Test
    void cannotTouchOtherUsersAddress() throws Exception {
        long a = createAddress("隔离", "13411112222", false);

        mockMvc.perform(delete("/api/v1/user/addresses/{id}", a)
                        .header("X-User-Id", 999_999L)
                        .header("Idempotency-Key", "del-" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
