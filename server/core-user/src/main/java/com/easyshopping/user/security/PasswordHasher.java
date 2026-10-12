package com.easyshopping.user.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 密码哈希：<b>PBKDF2-HMAC-SHA256（JDK 原生 {@link SecretKeyFactory}，零三方依赖）</b>。
 *
 * <p><b>为什么不再用单轮 SHA-256（P0-H）</b>：SHA-256 是为速度设计的哈希，GPU 上每秒可算数十亿次。
 * 即使加了随机盐（只防彩虹表，不防逐条爆破），拿到 {@code user_account} 表后常见密码仍是秒级破解。
 * PBKDF2 通过<b>密钥拉伸</b>把每次尝试的成本提高若干个数量级（{@value #DEFAULT_ITERATIONS} 次迭代）。</p>
 *
 * <p><b>存储格式</b>（带算法前缀 + 迭代数，便于将来调参与算法升级）：</p>
 * <pre>
 *   新：pbkdf2_sha256$&lt;iterations&gt;$&lt;saltBase64Url&gt;$&lt;hashBase64Url&gt;
 *   旧：s256$&lt;saltBase64Url&gt;$&lt;hashBase64Url&gt;        // 单轮 SHA-256，P0 历史数据，只读兼容
 * </pre>
 *
 * <p><b>存量升级（双写迁移）</b>：{@link #matches} 同时识别新旧格式（存量用户登录不受影响）；
 * 登录成功后调用 {@link #rehashIfNeeded(String, String)} 返回新格式串落库即完成升级。
 * P1 若换 BCrypt/Argon2，只需再加一个前缀分支与对应的 {@code needsRehash} 规则。</p>
 *
 * <p>安全约束：每用户独立 16B 随机盐；比较一律 {@link MessageDigest#isEqual} 常量时间防时序侧信道；
 * 格式非法一律返回 false 不抛异常（避免被用于探测）；raw 密码与哈希都不得落日志。</p>
 */
public final class PasswordHasher {

    /** 新格式算法前缀。 */
    private static final String PREFIX_PBKDF2 = "pbkdf2_sha256";

    /** 旧格式前缀（P0 单轮 SHA-256），仅用于读取存量数据。 */
    private static final String PREFIX_LEGACY = "s256";

    /** JDK 原生 PBKDF2 算法名（无三方依赖）。 */
    private static final String ALGORITHM_PBKDF2 = "PBKDF2WithHmacSHA256";

    /**
     * 默认迭代次数。OWASP 对 PBKDF2-HMAC-SHA256 的建议为 600k，本项目取 210k：
     * 在单机 P0 压测下登录 RT 可接受，且已比旧实现慢约 5 个数量级。
     * 提高只需改此处 + {@code needsRehash} 阈值，存量哈希登录成功时自动按新迭代数重算。
     */
    public static final int DEFAULT_ITERATIONS = 210_000;

    /** 盐长度（字节）：NIST 建议 ≥16B。 */
    private static final int SALT_BYTES = 16;

    /** 派生密钥长度（bit）：SHA-256 → 256bit。 */
    private static final int KEY_BITS = 256;

    /**
     * 迭代次数合理区间：低于下限视为损坏数据，高于上限视为恶意构造（防止拿库里伪造的
     * {@code iterations=2^31} 把登录接口打成 CPU 放大攻击）。
     */
    private static final int MIN_ITERATIONS = 1;
    private static final int MAX_ITERATIONS = 10_000_000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private PasswordHasher() {
    }

    /** 生成新格式哈希（PBKDF2 + 每用户独立随机盐）；raw 密码绝不写入日志/异常消息。 */
    public static String hash(String rawPassword) {
        return hash(rawPassword, DEFAULT_ITERATIONS);
    }

    /** 指定迭代次数生成哈希（测试与参数升级演练用；生产走 {@link #hash(String)}）。 */
    public static String hash(String rawPassword, int iterations) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] digest = pbkdf2(rawPassword, salt, iterations);
        return PREFIX_PBKDF2 + "$" + iterations + "$" + ENCODER.encodeToString(salt) + "$"
                + ENCODER.encodeToString(digest);
    }

    /**
     * 常量时间校验，<b>同时兼容新（PBKDF2）与旧（单轮 SHA-256）两种存储格式</b>：
     * 存量用户无需重置密码即可登录。
     *
     * <p>stored 格式非法时返回 false（不抛异常，避免被用于探测）。</p>
     */
    public static boolean matches(String rawPassword, String stored) {
        if (stored == null || rawPassword == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        byte[] salt;
        byte[] expected;
        byte[] actual;
        try {
            switch (parts[0]) {
                case PREFIX_PBKDF2 -> {
                    if (parts.length != 4) {
                        return false;
                    }
                    int iterations = Integer.parseInt(parts[1]);
                    if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
                        return false;
                    }
                    salt = DECODER.decode(parts[2]);
                    expected = DECODER.decode(parts[3]);
                    actual = pbkdf2(rawPassword, salt, iterations);
                }
                case PREFIX_LEGACY -> {
                    if (parts.length != 3) {
                        return false;
                    }
                    salt = DECODER.decode(parts[1]);
                    expected = DECODER.decode(parts[2]);
                    actual = sha256(concat(salt, rawPassword.getBytes(StandardCharsets.UTF_8)));
                }
                default -> {
                    return false;
                }
            }
        } catch (IllegalArgumentException e) {
            return false; // Base64 非法 / iterations 非数字
        }
        if (expected.length == 0) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 是否需要升级为新格式（登录成功后调用，true 则用 {@link #rehashIfNeeded} 的返回值落库）。
     * 判定口径：非 PBKDF2 前缀（存量 SHA-256）、段数不对、迭代数低于
     * {@link #DEFAULT_ITERATIONS}（参数调优后自动重算）。
     */
    public static boolean needsRehash(String stored) {
        if (stored == null) {
            return true;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX_PBKDF2.equals(parts[0])) {
            return true;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            return iterations < DEFAULT_ITERATIONS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /**
     * 登录成功后的哈希升级入口（双写迁移）：
     * 需要升级时返回新格式哈希，否则原样返回 stored（调用方可无条件写回）。
     */
    public static String rehashIfNeeded(String rawPassword, String stored) {
        return needsRehash(stored) ? hash(rawPassword) : stored;
    }

    private static byte[] pbkdf2(String rawPassword, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_BITS);
            try {
                return SecretKeyFactory.getInstance(ALGORITHM_PBKDF2).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 不可用", e);
        } catch (InvalidKeySpecException | IllegalArgumentException e) {
            throw new IllegalArgumentException("PBKDF2 参数非法", e);
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
