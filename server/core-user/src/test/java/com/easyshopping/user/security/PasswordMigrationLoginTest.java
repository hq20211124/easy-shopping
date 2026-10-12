package com.easyshopping.user.security;

import com.easyshopping.user.domain.UserAccount;
import com.easyshopping.user.repo.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 密码哈希迁移（双写）端到端回归测试（P0-H）。
 *
 * <p>用真实登录接口验证两件事：① <b>P0 存量 {@code s256$} 用户无需重置密码即可登录</b>
 * （{@code matches} 双格式兼容）；② 登录成功后按 {@link PasswordHasher#rehashIfNeeded}
 * 写回即可完成升级，升级后仍可登录且不再残留弱哈希。</p>
 *
 * <p>注：写入侧的 {@code rehashIfNeeded} 由 core-user 的登录链路调用（本测试第 ③ 步显式模拟该调用，
 * 断言升级算法本身的正确性）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-user-pwd-migrate-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class PasswordMigrationLoginTest {

    private static final String RAW_PASSWORD = "Legacy#9527";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserAccountRepository userRepo;

    /** 存量 s256$ 用户走真实登录接口仍成功，升级后可再次登录。 */
    @Test
    void 存量SHA256用户可登录并可升级为新格式() throws Exception {
        String mobile = "137" + String.format("%08d", System.nanoTime() % 100_000_000L);
        UserAccount legacyUser = new UserAccount(mobile, mobile, legacySha256(RAW_PASSWORD),
                "存量用户", "USER");
        userRepo.saveAndFlush(legacyUser);
        assertThat(legacyUser.getPasswordHash()).startsWith("s256$");

        // ① 存量弱哈希登录成功（双格式兼容，存量用户不踢不重置）
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + mobile + "\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // ② 错误密码仍被拒（兼容不是后门）
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + mobile + "\",\"password\":\"wrong\"}"))
                .andExpect(jsonPath("$.code").value(2002));

        // ③ 登录成功后的自动升级（rehash 写回）
        UserAccount stored = userRepo.findByUsernameAndDeletedFalse(mobile).orElseThrow();
        String upgraded = PasswordHasher.rehashIfNeeded(RAW_PASSWORD, stored.getPasswordHash());
        assertThat(upgraded).startsWith("pbkdf2_sha256$");
        stored.setPasswordHash(upgraded);
        userRepo.saveAndFlush(stored);

        // ④ 升级后登录仍成功，且弱哈希已从库里消失
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + mobile + "\",\"password\":\"" + RAW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        String finalHash = userRepo.findByUsernameAndDeletedFalse(mobile).orElseThrow().getPasswordHash();
        assertThat(finalHash).startsWith("pbkdf2_sha256$");
        assertThat(finalHash).doesNotContain(RAW_PASSWORD);
        assertThat(PasswordHasher.needsRehash(finalHash)).isFalse();
    }

    /** 新注册用户直接就是 PBKDF2 格式（注册路径无需迁移）。 */
    @Test
    void 新注册用户直接落新格式() throws Exception {
        String mobile = "136" + String.format("%08d", System.nanoTime() % 100_000_000L);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "reg-" + UUID.randomUUID())
                        .content("{\"mobile\":\"" + mobile + "\",\"code\":\"123456\","
                                + "\"password\":\"Fresh#9527\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        String hash = userRepo.findByUsernameAndDeletedFalse(mobile).orElseThrow().getPasswordHash();
        assertThat(hash).startsWith("pbkdf2_sha256$");
        assertThat(PasswordHasher.matches("Fresh#9527", hash)).isTrue();
    }

    /** 复刻 P0 旧实现（单轮 SHA-256 + 16B 随机盐）生成存量格式哈希。 */
    private static String legacySha256(String raw) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] pwd = raw.getBytes(StandardCharsets.UTF_8);
        byte[] input = new byte[salt.length + pwd.length];
        System.arraycopy(salt, 0, input, 0, salt.length);
        System.arraycopy(pwd, 0, input, salt.length, pwd.length);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "s256$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(digest);
    }
}
