package com.easyshopping.user.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PasswordHasher} 回归测试（P0-H：单轮 SHA-256 → PBKDF2-HMAC-SHA256）。
 *
 * <p>必测性质：① 新格式哈希可校验且迭代数达标；② 每用户独立随机盐 → 同密码两次哈希不同；
 * ③ <b>旧 {@code s256$} 存量用户仍能登录</b>；④ 登录成功可自动升级（rehash）且无需升级时不重算；
 * ⑤ 格式非法不抛异常。</p>
 */
class PasswordHasherTest {

    private static final String RAW = "Aa@123456";

    // ---------- ① 新格式 ----------

    @Test
    void 新格式哈希可校验且迭代数达标() {
        String stored = PasswordHasher.hash(RAW);

        assertThat(stored).startsWith("pbkdf2_sha256$");
        assertThat(stored.split("\\$")).hasSize(4); // 前缀$迭代数$盐$哈希
        assertThat(Integer.parseInt(stored.split("\\$")[1])).isGreaterThanOrEqualTo(100_000);
        assertThat(PasswordHasher.matches(RAW, stored)).isTrue();
        assertThat(PasswordHasher.matches("wrong-password", stored)).isFalse();
        assertThat(PasswordHasher.matches(RAW + " ", stored)).isFalse();
    }

    // ---------- ② 每用户独立随机盐 ----------

    @Test
    void 盐不同则哈希不同且均可校验() {
        String first = PasswordHasher.hash(RAW);
        String second = PasswordHasher.hash(RAW);

        assertThat(first).isNotEqualTo(second); // 随机盐 → 同一密码两次哈希不同（防彩虹表）
        assertThat(first.split("\\$")[2]).isNotEqualTo(second.split("\\$")[2]); // 盐段不同
        assertThat(PasswordHasher.matches(RAW, first)).isTrue();
        assertThat(PasswordHasher.matches(RAW, second)).isTrue();
    }

    @Test
    void 不同迭代数派生的哈希互不通过() {
        String weak = PasswordHasher.hash(RAW, 1000);
        String strong = PasswordHasher.hash(RAW, 2000);

        assertThat(PasswordHasher.matches(RAW, weak)).isTrue();
        assertThat(PasswordHasher.matches(RAW, strong)).isTrue();
        // 篡改迭代数段不得让密码「蒙混过关」
        String tampered = "pbkdf2_sha256$" + 2000 + "$" + weak.split("\\$")[2] + "$" + weak.split("\\$")[3];
        assertThat(PasswordHasher.matches(RAW, tampered)).isFalse();
    }

    // ---------- ③ 旧格式（存量）兼容 ----------

    @Test
    void 旧SHA256格式用户仍能登录() {
        String legacy = legacySha256(RAW);

        assertThat(legacy).startsWith("s256$");
        assertThat(PasswordHasher.matches(RAW, legacy)).isTrue();
        assertThat(PasswordHasher.matches("wrong-password", legacy)).isFalse();
    }

    // ---------- ④ 登录成功自动升级（rehash） ----------

    @Test
    void 旧格式登录成功后自动升级为新格式() {
        String legacy = legacySha256(RAW);

        // 登录成功路径：校验通过 → 取升级后的串写回 user_account.password_hash
        assertThat(PasswordHasher.matches(RAW, legacy)).isTrue();
        assertThat(PasswordHasher.needsRehash(legacy)).isTrue();
        String upgraded = PasswordHasher.rehashIfNeeded(RAW, legacy);

        assertThat(upgraded).startsWith("pbkdf2_sha256$");
        assertThat(PasswordHasher.matches(RAW, upgraded)).isTrue(); // 升级后仍可登录
        assertThat(PasswordHasher.needsRehash(upgraded)).isFalse();
        // 升级后旧哈希彻底不可用（数据不再残留弱哈希）
        assertThat(PasswordHasher.matches(RAW, legacy)).isTrue(); // 仅校验能力，库里已不再保存旧串
    }

    @Test
    void 已是新格式则不重复升级() {
        String stored = PasswordHasher.hash(RAW);

        assertThat(PasswordHasher.needsRehash(stored)).isFalse();
        assertThat(PasswordHasher.rehashIfNeeded(RAW, stored)).isEqualTo(stored); // 原样返回，不浪费 CPU
    }

    @Test
    void 迭代数低于默认阈值时视为需升级() {
        String weak = PasswordHasher.hash(RAW, 1000);

        assertThat(PasswordHasher.needsRehash(weak)).isTrue();
        String upgraded = PasswordHasher.rehashIfNeeded(RAW, weak);
        assertThat(Integer.parseInt(upgraded.split("\\$")[1]))
                .isEqualTo(PasswordHasher.DEFAULT_ITERATIONS);
        assertThat(PasswordHasher.matches(RAW, upgraded)).isTrue();
    }

    // ---------- ⑤ 格式非法不抛异常（防探测 / 防 CPU 放大） ----------

    @Test
    void 非法格式一律校验失败且不抛异常() {
        String[] broken = {
                null,
                "",
                "   ",
                "plaintext",
                "s256$only-two",
                "pbkdf2_sha256$210000$onlythree",
                "pbkdf2_sha256$notanumber$AAAA$BBBB",
                "pbkdf2_sha256$210000$!!!$BBBB",
                "bcrypt$2a$10$whatever",
        };
        for (String stored : broken) {
            assertThat(PasswordHasher.matches(RAW, stored)).as("stored=%s", stored).isFalse();
        }
        assertThat(PasswordHasher.matches(null, PasswordHasher.hash(RAW))).isFalse();
        assertThat(PasswordHasher.matches(RAW, null)).isFalse();
    }

    @Test
    void 超大迭代数视为非法_防止CPU放大攻击() {
        String forged = "pbkdf2_sha256$2147483647$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAA";

        // matches 直接判非法，不会真的去算 21 亿次迭代（否则攻击者改一下库里数据即可打满 CPU）
        assertThat(PasswordHasher.matches(RAW, forged)).isFalse();
    }

    // ---------- helpers ----------

    /** 复刻 P0 旧实现（单轮 SHA-256 + 16B 随机盐，{@code s256$salt$hash}），用于存量兼容验证。 */
    private static String legacySha256(String raw) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] input = new byte[salt.length + raw.getBytes(StandardCharsets.UTF_8).length];
        System.arraycopy(salt, 0, input, 0, salt.length);
        System.arraycopy(raw.getBytes(StandardCharsets.UTF_8), 0, input, salt.length,
                raw.getBytes(StandardCharsets.UTF_8).length);
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
