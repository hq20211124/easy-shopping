package com.easyshopping.common.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * JWT（HS256）签发与校验，JDK 原生实现、零三方依赖。
 *
 * <p>口径：Access Token 默认 2 小时、Refresh Token 默认 7 天（可配 {@code app.jwt.*}）；
 * Refresh Token 轮换/黑名单、单端互踢见 plans/admin-backend.md 2.1 / plans/android-backend.md 1.6.4，P1 落地。</p>
 */
public class JwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;
    private final SecretKeySpec secretKey;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public JwtService(
            @Value("${app.jwt.secret:dev-only-secret-change-me-in-prod-0123456789abcdef}") String secret,
            @Value("${app.jwt.access-ttl-seconds:7200}") long accessTtlSeconds,
            @Value("${app.jwt.refresh-ttl-seconds:604800}") long refreshTtlSeconds) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("app.jwt.secret 长度必须 >= 32 字节（生产环境务必替换默认值）");
        }
        this.objectMapper = new ObjectMapper();
        this.secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    public String generateAccessToken(long userId, List<String> roles) {
        return generate(userId, roles, TYPE_ACCESS, accessTtlSeconds);
    }

    public String generateRefreshToken(long userId) {
        return generate(userId, List.of(), TYPE_REFRESH, refreshTtlSeconds);
    }

    /**
     * 解析并校验 Token（签名 + 过期时间）。
     *
     * @throws InvalidTokenException 签名不匹配 / 格式非法 / 已过期
     */
    public JwtClaims parse(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new InvalidTokenException("token 格式非法");
            }
            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] actual = B64D.decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new InvalidTokenException("签名校验失败");
            }
            JsonNode payload = objectMapper.readTree(B64D.decode(parts[1]));
            String type = payload.path("typ").asText("");
            if (!TYPE_ACCESS.equals(type) && !TYPE_REFRESH.equals(type)) {
                throw new InvalidTokenException("token 类型非法: " + type);
            }
            long exp = payload.path("exp").asLong(0);
            if (exp <= System.currentTimeMillis() / 1000) {
                throw new InvalidTokenException("token 已过期");
            }
            JsonNode rolesNode = payload.path("roles");
            List<String> roles = rolesNode.isArray()
                    ? objectMapper.convertValue(rolesNode, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class))
                    : List.of();
            return new JwtClaims(payload.path("sub").asLong(0), roles, type, exp, payload.path("jti").asText());
        } catch (InvalidTokenException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidTokenException("token 解析失败", e);
        }
    }

    private String generate(long userId, List<String> roles, String type, long ttlSeconds) {
        long now = System.currentTimeMillis() / 1000;
        ObjectNode header = objectMapper.createObjectNode().put("alg", "HS256").put("typ", "JWT");
        ObjectNode payload = objectMapper.createObjectNode()
                .put("sub", userId)
                .put("typ", type)
                .put("iat", now)
                .put("exp", now + ttlSeconds)
                .put("jti", UUID.randomUUID().toString().replace("-", ""));
        payload.set("roles", objectMapper.valueToTree(roles));
        try {
            String head = B64.encodeToString(objectMapper.writeValueAsBytes(header));
            String body = B64.encodeToString(objectMapper.writeValueAsBytes(payload));
            return head + "." + body + "." + B64.encodeToString(sign(head + "." + body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JWT 签名失败", e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("JWT 序列化失败", e);
        }
    }

    private byte[] sign(String content) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(secretKey);
        return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
    }
}
