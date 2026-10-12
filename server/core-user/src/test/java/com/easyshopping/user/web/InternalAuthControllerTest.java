package com.easyshopping.user.web;

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

/**
 * 内部端点测试（admin-bff 依赖）：凭据校验 + 账号管理 + user.deleted 事件（PIPL）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-user-internal-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class InternalAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void verifyReturnsRolesForAdminUser() throws Exception {
        // 创建一个 ADMIN 账号（admin-bff 系统管理链路）
        String username = "admin-" + System.nanoTime();
        mockMvc.perform(post("/api/v1/internal/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .content("{\"username\":\"" + username + "\",\"password\":\"admin-pass-123\","
                                + "\"nickname\":\"运营管理员\",\"roles\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").isNumber());

        mockMvc.perform(post("/api/v1/internal/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"admin-pass-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.data.nickname").value("运营管理员"));
    }

    @Test
    void verifyWrongPasswordReturns2002() throws Exception {
        String username = "admin-" + System.nanoTime();
        mockMvc.perform(post("/api/v1/internal/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .content("{\"username\":\"" + username + "\",\"password\":\"admin-pass-123\",\"roles\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/internal/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"bad\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2002));
    }

    @Test
    void internalListFiltersByRole() throws Exception {
        String username = "admin-" + System.nanoTime();
        mockMvc.perform(post("/api/v1/internal/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .content("{\"username\":\"" + username + "\",\"password\":\"admin-pass-123\",\"roles\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(get("/api/v1/internal/users")
                        .param("role", "ADMIN")
                        .param("keyword", username)
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].username").value(username))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("admin-pass-123");
    }

    @Test
    void deletePublishesUserDeletedEventAndDisablesLogin() throws Exception {
        String username = "admin-" + System.nanoTime();
        MvcResult create = mockMvc.perform(post("/api/v1/internal/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "create-" + UUID.randomUUID())
                        .content("{\"username\":\"" + username + "\",\"password\":\"admin-pass-123\",\"roles\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long userId = objectMapper.readTree(create.getResponse().getContentAsString())
                .path("data").path("userId").asLong();

        mockMvc.perform(delete("/api/v1/internal/users/{id}", userId)
                        .header("Idempotency-Key", "del-" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true));

        // 删除后凭据校验失效（软删不可登录）
        mockMvc.perform(post("/api/v1/internal/auth/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"admin-pass-123\"}"))
                .andExpect(jsonPath("$.code").value(2001));
    }
}
