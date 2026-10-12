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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * refresh 换发与旧 token 宽限（mem H2）：
 * ① refresh 换新 access+refresh（轮换）；② 旧 refresh 在 60s 宽限期内仍可用（并发刷新竞态容忍）；
 * ③ logout 后 refresh 立即失效（1003）；④ access token 不可用于 refresh（1003）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-user-refresh-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AuthRefreshTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode login() throws Exception {
        String mobile = "139" + String.format("%08d", System.nanoTime() % 100_000_000L);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "reg-" + UUID.randomUUID())
                        .content("{\"mobile\":\"" + mobile + "\",\"code\":\"123456\",\"password\":\"pass-123456\"}"))
                .andExpect(status().isOk());
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + mobile + "\",\"password\":\"pass-123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode refresh(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void refreshRotatesTokens() throws Exception {
        JsonNode loginData = login();
        String oldRefresh = loginData.path("refreshToken").asText();

        JsonNode refreshed = refresh(oldRefresh);
        assertThat(refreshed.path("code").asInt()).isZero();
        JsonNode data = refreshed.path("data");
        assertThat(data.path("accessToken").asText()).isNotBlank();
        assertThat(data.path("refreshToken").asText()).isNotBlank();
        // 轮换：新 refresh token ≠ 旧 token
        assertThat(data.path("refreshToken").asText()).isNotEqualTo(oldRefresh);
        assertThat(data.path("userId").asLong()).isEqualTo(loginData.path("userId").asLong());
    }

    @Test
    void oldRefreshTokenUsableWithinGracePeriod() throws Exception {
        JsonNode loginData = login();
        String oldRefresh = loginData.path("refreshToken").asText();

        // 第一次轮换
        assertThat(refresh(oldRefresh).path("code").asInt()).isZero();
        // 宽限期内（默认 60s）旧 token 仍可换新 —— 并发刷新竞态容忍
        JsonNode again = refresh(oldRefresh);
        assertThat(again.path("code").asInt()).isZero();
        assertThat(again.path("data").path("accessToken").asText()).isNotBlank();
    }

    @Test
    void logoutRevokesRefreshTokenImmediately() throws Exception {
        JsonNode loginData = login();
        String refresh = loginData.path("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));

        // 吊销后立即失效（即使仍在宽限窗口内）
        JsonNode afterLogout = refresh(refresh);
        assertThat(afterLogout.path("code").asInt()).isEqualTo(1003);
    }

    @Test
    void accessTokenCannotBeUsedAsRefreshToken() throws Exception {
        JsonNode loginData = login();
        String access = loginData.path("accessToken").asText();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + access + "\"}"))
                .andExpect(status().isUnauthorized())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).isEqualTo(1003);
    }

    @Test
    void forgedRefreshTokenRejected() throws Exception {
        JsonNode body = refresh("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOjF9.forge");
        assertThat(body.path("code").asInt()).isEqualTo(1003);
    }
}
