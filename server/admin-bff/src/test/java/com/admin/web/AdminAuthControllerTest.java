package com.admin.web;

import com.admin.client.ClientDtos.VerifyResult;
import com.admin.client.CoreUserClient;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端认证测试（mem H2 + mock core-user）：
 * ① 登录成功/密码错误(2002)/非管理员(1004)
 * ② refresh 轮换 + 60s 宽限 + logout 吊销
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-bff-auth-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AdminAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CoreUserClient coreUserClient;

    private static final String PASSWORD = "admin-pass-123456";

    private void stubVerify(String password, VerifyResult result) {
        when(coreUserClient.verify(anyString(), org.mockito.ArgumentMatchers.eq(password))).thenReturn(result);
    }

    private VerifyResult adminUser(long userId) {
        return new VerifyResult(userId, "admin1", "管理员", List.of("ADMIN"), 1);
    }

    private MvcResult login(String password) throws Exception {
        return mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin1\",\"password\":\"" + password + "\"}"))
                .andReturn();
    }

    @Test
    void loginSuccessReturnsTokenAndRefreshCookie() throws Exception {
        stubVerify(PASSWORD, adminUser(900001L));

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin1\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresIn").value(7200))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                // Refresh Token 只走 httpOnly Cookie（plans 2.1.2），body 不回传
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(cookie().exists("ADMIN_RT"))
                .andExpect(cookie().httpOnly("ADMIN_RT", true))
                .andExpect(cookie().path("ADMIN_RT", "/api/admin"));
    }

    @Test
    void loginWithWrongPasswordReturns2002() throws Exception {
        when(coreUserClient.verify(anyString(), anyString()))
                .thenThrow(new BizException(BizErrorCode.PASSWORD_ERROR));

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin1\",\"password\":\"bad-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2002));
    }

    @Test
    void nonAdminLoginReturns403And1004() throws Exception {
        stubVerify(PASSWORD, new VerifyResult(900002L, "buyer1", "买家", List.of("USER"), 1));

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer1\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1004));
    }

    @Test
    void refreshRotatesTokenAndGrantsGraceToOldOne() throws Exception {
        stubVerify(PASSWORD, adminUser(900003L));
        MvcResult loginResult = login(PASSWORD);
        Cookie oldRefresh = loginResult.getResponse().getCookie("ADMIN_RT");
        assertThat(oldRefresh).isNotNull();

        // 第一次 refresh：轮换出新 refresh
        MvcResult refresh1 = mockMvc.perform(post("/api/admin/auth/refresh")
                        .cookie(oldRefresh)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        Cookie newRefresh = refresh1.getResponse().getCookie("ADMIN_RT");
        assertThat(newRefresh).isNotNull();
        assertThat(newRefresh.getValue()).isNotEqualTo(oldRefresh.getValue());

        // 旧 refresh 在 60s 宽限期内仍可用（并发刷新竞态）
        mockMvc.perform(post("/api/admin/auth/refresh")
                        .cookie(oldRefresh)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        stubVerify(PASSWORD, adminUser(900004L));
        MvcResult loginResult = login(PASSWORD);
        Cookie refresh = loginResult.getResponse().getCookie("ADMIN_RT");

        mockMvc.perform(post("/api/admin/auth/logout")
                        .cookie(refresh)
                        .header("X-User-Id", 900004L)
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 吊销后再 refresh → 1003
        mockMvc.perform(post("/api/admin/auth/refresh").cookie(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1003));
    }
}
