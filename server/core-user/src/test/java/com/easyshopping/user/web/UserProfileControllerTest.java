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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 用户资料脱敏测试：手机号 138****8000 / 邮箱 z***@example.com（mem H2）。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-user-profile-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class UserProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private long register(String mobile) throws Exception {
        MvcResult result = mockMvc.perform(doRegister(mobile))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("userId").asLong();
    }

    private org.springframework.test.web.servlet.RequestBuilder doRegister(String mobile) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "reg-" + UUID.randomUUID())
                .content("{\"mobile\":\"" + mobile + "\",\"code\":\"123456\",\"password\":\"pass-123456\"}");
    }

    @Test
    void profileMobileIsMasked() throws Exception {
        long userId = register("13800138000");

        mockMvc.perform(get("/api/v1/user/profile").header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mobile").value("138****8000"))
                .andExpect(jsonPath("$.data.username").value("13800138000"));
    }

    @Test
    void emailIsMaskedAfterUpdate() throws Exception {
        long userId = register("13800138111");

        mockMvc.perform(put("/api/v1/user/profile")
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", "put-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"测试昵称\",\"email\":\"zhangsan@example.com\",\"gender\":1,\"birthday\":\"1990-05-20\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(true));

        mockMvc.perform(get("/api/v1/user/profile").header("X-User-Id", userId))
                .andExpect(jsonPath("$.data.nickname").value("测试昵称"))
                .andExpect(jsonPath("$.data.email").value("z***@example.com"))
                .andExpect(jsonPath("$.data.gender").value(1))
                .andExpect(jsonPath("$.data.birthday").value("1990-05-20"));
    }

    @Test
    void profileWithoutLoginReturns1002() throws Exception {
        mockMvc.perform(get("/api/v1/user/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void invalidBirthdayRejectedAs1001() throws Exception {
        long userId = register("13800138222");

        MvcResult result = mockMvc.perform(put("/api/v1/user/profile")
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", "put-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"birthday\":\"1990/05/20\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).isEqualTo(1001);
    }
}
