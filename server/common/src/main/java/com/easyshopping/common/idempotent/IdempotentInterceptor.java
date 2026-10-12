package com.easyshopping.common.idempotent;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Idempotent} 拦截器：P0 内存去重。
 *
 * <p><b>生产注意</b>：内存 Map 仅单实例有效，服务多副本部署时幂等会失效；
 * P1 必须替换为 Redis {@code SET key NX EX ttl} 原子占位（同机房 Redis 实例组见 plans 1.3）。
 * Key 维度建议带上服务名+接口名前缀，避免跨接口撞键。</p>
 */
public class IdempotentInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(IdempotentInterceptor.class);
    private static final String FALLBACK_KEY_HEADER = "X-Idempotency-Key";

    /** key -> 过期时间戳（epoch 秒）。 */
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        Idempotent idem = method.getMethodAnnotation(Idempotent.class);
        if (idem == null) {
            return true;
        }
        String key = request.getHeader(idem.keyHeader());
        if (key == null || key.isBlank()) {
            key = request.getHeader(FALLBACK_KEY_HEADER);
        }
        if (key == null || key.isBlank()) {
            // 写接口必须携带幂等键（plans/admin-backend.md 10.1.1）
            throw new BizException(BizErrorCode.PARAM_ERROR, "缺少幂等键 " + idem.keyHeader());
        }
        long now = System.currentTimeMillis() / 1000;
        cleanupIfTooLarge(now);
        String scopedKey = request.getMethod() + ":" + request.getRequestURI() + ":" + key;
        long expireAt = now + idem.ttlSeconds();
        Long prev = seen.putIfAbsent(scopedKey, expireAt);
        if (prev == null) {
            return true; // 首次请求，占位成功
        }
        if (prev > now) {
            log.warn("idempotent conflict: {}", scopedKey);
            throw new BizException(BizErrorCode.IDEMPOTENT_CONFLICT);
        }
        // 旧占位已过期：CAS 替换；失败说明并发请求已抢注
        if (seen.replace(scopedKey, prev, expireAt)) {
            return true;
        }
        log.warn("idempotent conflict (concurrent): {}", scopedKey);
        throw new BizException(BizErrorCode.IDEMPOTENT_CONFLICT);
    }

    private void cleanupIfTooLarge(long now) {
        if (seen.size() > 100_000) {
            seen.entrySet().removeIf(e -> e.getValue() <= now);
        }
    }
}
