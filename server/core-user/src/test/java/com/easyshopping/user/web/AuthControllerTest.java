package com.easyshopping.user.web;

import com.easyshopping.user.domain.UserAccount;
import com.easyshopping.user.repo.UserAccountRepository;
import com.easyshopping.user.security.PasswordHasher;
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
 * 认证流程测试（mem H2）：注册/登录成功/失败/锁定、密码不落库明文。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-user-auth-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserAccountRepository userRepo;

    private String uniqueMobile() {
        return "138" + String.format("%08d", System.nanoTime() % 100_000_000L);
    }

    private void register(String mobile, String password) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "reg-" + UUID.randomUUID())
                        .content("{\"mobile\":\"" + mobile + "\",\"code\":\"123456\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").isNumber())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.isNewUser").value(true));
    }

    @Test
    void registerAndLoginSuccess() throws Exception {
        String mobile = uniqueMobile();
        register(mobile, "pass-123456");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + mobile + "\",\"password\":\"pass-123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.username").value(mobile))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(7200));
    }

    @Test
    void loginWithWrongPasswordReturns2002() throws Exception {
        String mobile = uniqueMobile();
        register(mobile, "pass-123456");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + mobile + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2002));
    }

    @Test
    void loginWithUnknownUserReturns2001() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody-xyz\",\"password\":\"x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2001));
    }

    @Test
    void fiveFailuresLockAccount() throws Exception {
        String mobile = uniqueMobile();
        register(mobile, "pass-123456");

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + mobile + "\",\"password\":\"bad-" + i + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(2002));
        }

        // 锁定后即使密码正确也拒绝
        MvcResult locked = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + mobile + "\",\"password\":\"pass-123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2002))
                .andReturn();
        assertThat(locked.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("锁定");

        UserAccount user = userRepo.findByUsernameAndDeletedFalse(mobile).orElseThrow();
        assertThat(user.isLocked()).isTrue();
    }

    @Test
    void passwordIsNeverStoredInPlaintext() throws Exception {
        String mobile = uniqueMobile();
        register(mobile, "super-secret-9527");

        UserAccount user = userRepo.findByUsernameAndDeletedFalse(mobile).orElseThrow();
        // P0-H：算法由单轮 SHA-256（s256$）升级为 PBKDF2-HMAC-SHA256（pbkdf2_sha256$迭代数$盐$哈希）
        assertThat(user.getPasswordHash()).startsWith("pbkdf2_sha256$");
        assertThat(user.getPasswordHash()).doesNotContain("super-secret-9527");
        assertThat(PasswordHasher.matches("super-secret-9527", user.getPasswordHash())).isTrue();
        assertThat(PasswordHasher.matches("other", user.getPasswordHash())).isFalse();
    }

    @Test
    void registerWithWrongSmsCodeReturns2003() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "reg-" + UUID.randomUUID())
                        .content("{\"mobile\":\"" + uniqueMobile() + "\",\"code\":\"000000\",\"password\":\"pass-123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2003));
    }
}
