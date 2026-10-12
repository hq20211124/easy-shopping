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
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link Idempotent} 拦截器：P0 内存去重。
 *
 * <p><b>两态语义</b>（P1-2 修复，根因：占位只在 preHandle 写入、业务失败不释放，用户无法用同一提交意图重试）：
 * <ul>
 *   <li>{@code preHandle} 写入 {@code PROCESSING} 占位——这是「提交进行中」的闸门，
 *       <b>并发同键请求在 PROCESSING 期间仍然拒绝</b>（保持原有语义：防止真实重复提交产生两笔副作用）；</li>
 *   <li>{@code afterCompletion} 正常完成 → 置 {@code SUCCESS}，此后同键重试返回 1005 冲突；</li>
 *   <li>{@code afterCompletion} 业务异常（{@link BizException}）→ <b>删除占位</b>，同键可重试
 *       （下单「库存不足 3002」「券不可用 4002」这类高频失败不再吃掉幂等键）；</li>
 *   <li>系统异常/超时（非 BizException）→ <b>保留 PROCESSING</b>，等 TTL 自然过期，
 *       防止已产生部分副作用的请求被盲目重放。</li>
 * </ul>
 *
 * <p><b>为什么 {@code afterCompletion} 还要看请求属性</b>：生产链路里业务异常由
 * {@code GlobalExceptionHandler}（{@code @RestControllerAdvice}）消化，DispatcherServlet 会把
 * {@code ex} 置空后再回调 {@code afterCompletion}——只看 {@code ex != null} 会把所有业务失败误判为成功。
 * 因此 {@code GlobalExceptionHandler} 统一打标（业务失败 {@link #ATTR_BUSINESS_ERROR} /
 * 系统失败 {@link #ATTR_SYSTEM_ERROR}），两条路径都覆盖
 * （带 advice → 看属性；不带 advice → 看 ex）。</p>
 *
 * <p><b>生产注意</b>：内存 Map 仅单实例有效，服务多副本部署时幂等会失效；
 * P1 必须替换为 Redis {@code SET key NX EX ttl} 原子占位（同机房 Redis 实例组见 plans 1.3），
 * 状态位用 value（PROCESSING/SUCCESS）表达，删除用 {@code DEL key value} 比对避免删掉别人的占位。
 * Key 维度建议带上服务名+接口名前缀，避免跨接口撞键。</p>
 */
public class IdempotentInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(IdempotentInterceptor.class);
    private static final String FALLBACK_KEY_HEADER = "X-Idempotency-Key";

    /** 请求属性：本次请求成功抢到的占位声明（{@link Claim}）。仅 preHandle 成功写入时才有。 */
    static final String ATTR_CLAIM = IdempotentInterceptor.class.getName() + ".CLAIM";

    /**
     * 请求属性：本次请求以业务异常收尾（由 {@code GlobalExceptionHandler} 打标）。
     * 对外暴露给 {@code GlobalExceptionHandler} 使用，幂等拦截器据此释放占位。
     */
    public static final String ATTR_BUSINESS_ERROR = IdempotentInterceptor.class.getName() + ".BIZ_ERROR";

    /**
     * 请求属性：本次请求以系统异常收尾（由 {@code GlobalExceptionHandler} 打标）。
     * 幂等拦截器据此保留 {@code PROCESSING} 占位，等 TTL 自然过期。
     */
    public static final String ATTR_SYSTEM_ERROR = IdempotentInterceptor.class.getName() + ".SYS_ERROR";

    /** key -> 占位（{@code PROCESSING} / {@code SUCCESS} + 过期时间戳 epoch 秒 + 本次占位唯一 token）。 */
    private final Map<String, Entry> seen = new ConcurrentHashMap<>();

    /** 占位唯一 token 生成器（保证 {@code remove(key, myEntry)} 只会删掉自己那份占位）。 */
    private final AtomicLong tokenSeq = new AtomicLong();

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
        Entry claim = new Entry(expireAt, false, tokenSeq.incrementAndGet());
        while (true) {
            Entry prev = seen.putIfAbsent(scopedKey, claim);
            if (prev == null) {
                request.setAttribute(ATTR_CLAIM, new Claim(scopedKey, claim));
                return true; // 首次请求，占位成功（PROCESSING）
            }
            if (prev.expireAt() <= now) {
                // 旧占位已过期（无论 PROCESSING/SUCCESS）：CAS 替换；失败说明并发请求已抢注，重新读一轮
                if (seen.replace(scopedKey, prev, claim)) {
                    request.setAttribute(ATTR_CLAIM, new Claim(scopedKey, claim));
                    return true;
                }
                continue;
            }
            // 仍在 TTL 内：SUCCESS（已成功过）或 PROCESSING（并发提交中）→ 都判重冲突
            log.warn("idempotent conflict: {} state={}", scopedKey, prev.success() ? "SUCCESS" : "PROCESSING");
            throw new BizException(BizErrorCode.IDEMPOTENT_CONFLICT);
        }
    }

    /**
     * 收尾：把 PROCESSING 占位推进到终态。
     * <ul>
     *   <li>正常完成 → SUCCESS（此后同键重试 1005）；</li>
     *   <li>业务异常 → 删除占位（同键可重试）；</li>
     *   <li>系统异常/超时 → 保留 PROCESSING（防重复提交副作用，等 TTL 过期）。</li>
     * </ul>
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!(request.getAttribute(ATTR_CLAIM) instanceof Claim claim)) {
            return; // preHandle 未成功占位（未标注 @Idempotent / 缺 key 抛错）→ 无占位可收尾
        }
        // advice（@RestControllerAdvice）消化异常后 DispatcherServlet 会把 ex 置空，两条路径都要看
        boolean businessError = ex instanceof BizException
                || Boolean.TRUE.equals(request.getAttribute(ATTR_BUSINESS_ERROR));
        if (businessError) {
            // 带值删除：只删自己那份占位，绝不误删后来者的 SUCCESS
            if (seen.remove(claim.key(), claim.entry())) {
                log.info("idempotent released after business error: {}", claim.key());
            }
            return;
        }
        boolean systemError = ex != null || Boolean.TRUE.equals(request.getAttribute(ATTR_SYSTEM_ERROR));
        if (systemError) {
            log.error("idempotent claim kept as PROCESSING after system error: {} cause={}",
                    claim.key(), ex == null ? "advice-handled" : ex.getClass().getName());
            return;
        }
        seen.computeIfPresent(claim.key(), (k, cur) -> cur.equals(claim.entry()) ? cur.asSuccess() : cur);
    }

    private void cleanupIfTooLarge(long now) {
        if (seen.size() > 100_000) {
            seen.entrySet().removeIf(e -> e.getValue().expireAt() <= now);
        }
    }

    /**
     * 占位条目。{@code token} 让「带值删除/推进状态」只作用于自己写入的那一份，
     * 避免并发下误伤别人（可能值相同）的占位。
     */
    private record Entry(long expireAt, boolean success, long token) {

        Entry asSuccess() {
            return new Entry(expireAt, true, token);
        }
    }

    /** 本次请求成功抢到的占位（写入 request 属性，供 afterCompletion 收尾）。 */
    private record Claim(String key, Entry entry) {
    }
}
