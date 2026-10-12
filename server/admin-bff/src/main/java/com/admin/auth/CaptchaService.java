package com.admin.auth;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图形验证码（P0 内存存储 + SVG 渲染，零图片依赖）。
 *
 * <p>登录失败 3 次后强制校验（plans/admin-backend.md 2.5.1）——P0 由前端在失败 ≥3 次时
 * 先调 GET /api/admin/auth/captcha 并在登录请求携带 captchaId/captchaCode；
 * TODO(P1) 服务端强制（core-user 侧失败计数经 internal verify 暴露）。</p>
 *
 * <p>注意：/api/admin/auth/captcha 需 Token（网关白名单未含该路径，本代理无权改 gateway；
 * 联调时请网关负责人加白，见 README 依赖清单）。登录页场景下由已在 Cookie 中的
 * access token 残留期访问，或后端直接下发。</p>
 */
@Service
public class CaptchaService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final long TTL_SECONDS = 300;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final long ttlSeconds;

    public CaptchaService(@Value("${app.auth.captcha-ttl-seconds:300}") long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public record Captcha(String captchaId, String image) {
    }

    private record Entry(String code, long expireAt) {
    }

    public Captcha generate() {
        cleanup();
        StringBuilder code = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        store.put(id, new Entry(code.toString(), System.currentTimeMillis() / 1000 + ttlSeconds));
        return new Captcha(id, svg(code.toString()));
    }

    /** captchaId 为空不校验（P0 前端可选携带）；提供了则强校验。 */
    public void validate(String captchaId, String captchaCode) {
        if (captchaId == null || captchaId.isBlank()) {
            return;
        }
        Entry entry = store.remove(captchaId);
        if (entry == null) {
            throw new BizException(BizErrorCode.CAPTCHA_ERROR, "验证码不存在或已使用");
        }
        if (entry.expireAt() < System.currentTimeMillis() / 1000) {
            throw new BizException(BizErrorCode.CAPTCHA_EXPIRED);
        }
        if (!entry.code().equalsIgnoreCase(captchaCode == null ? "" : captchaCode.trim())) {
            throw new BizException(BizErrorCode.CAPTCHA_ERROR);
        }
    }

    /** SVG 文本验证码（浏览器可直接渲染；P1 换干扰线/点的图片验证码或行为验证码）。 */
    private String svg(String code) {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='120' height='40'>"
                + "<rect width='120' height='40' fill='#f0f2f5'/>"
                + "<text x='24' y='28' font-size='22' font-family='monospace' fill='#1677ff'>"
                + code + "</text></svg>";
        return "data:image/svg+xml;base64," + Base64.getEncoder()
                .encodeToString(svg.getBytes(StandardCharsets.UTF_8));
    }

    private void cleanup() {
        long now = System.currentTimeMillis() / 1000;
        store.entrySet().removeIf(e -> e.getValue().expireAt() < now);
    }
}
