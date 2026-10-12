package com.easyshopping.user.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码哈希（P0：JDK 原生 SHA-256 + 随机盐，零额外依赖）。
 *
 * <p>TODO(P1)：迁移到 BCrypt（$2a$，cost=10）。迁移时采用双写校验：
 * 登录成功后按新算法重写哈希，逐步完成存量用户升级（plans/admin-backend.md 2.1 未强制算法，
 * 骨架阶段以"不引入重依赖 + 不落明文/不落日志"为底线）。</p>
 *
 * <p>存储格式：{@code s256$<saltBase64>$<hashBase64>}，比较使用常量时间 equals 防时序侧信道。</p>
 */
public final class PasswordHasher {

    private static final String PREFIX = "s256";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SALT_BYTES = 16;

    private PasswordHasher() {
    }

    /** 生成哈希；raw 密码绝不写入日志/异常消息。 */
    public static String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] digest = sha256(concat(salt, rawPassword.getBytes(StandardCharsets.UTF_8)));
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return PREFIX + "$" + encoder.encodeToString(salt) + "$" + encoder.encodeToString(digest);
    }

    /** 常量时间比较；stored 格式非法时返回 false（不抛异常，避免被用于探测）。 */
    public static boolean matches(String rawPassword, String stored) {
        if (stored == null || rawPassword == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] salt = decoder.decode(parts[1]);
            byte[] expected = decoder.decode(parts[2]);
            byte[] actual = sha256(concat(salt, rawPassword.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
