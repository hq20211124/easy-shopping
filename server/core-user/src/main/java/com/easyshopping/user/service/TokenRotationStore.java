package com.easyshopping.user.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Refresh Token 轮换记录（P0：进程内内存，单实例有效）。
 *
 * <p>轮换语义（plans/admin-backend.md 2.1.2）：每次 refresh 签发新 Refresh Token，
 * 旧 Refresh Token 进入 <b>60s 宽限期</b>（容忍并发刷新竞态，宽限期内仍可换新），宽限期后作废；
 * logout 主动吊销（Long.MAX_VALUE，永不过期）。</p>
 *
 * <p>TODO(P1)：多实例部署必须换 Redis —— {@code SET auth:refresh:{userId}:{jti} EX ttl NX}，
 * 轮换时旧 key 改短 TTL（60s）实现宽限，吊销即 DEL/写黑名单；同时配合 deviceId 绑定
 * （plans/android-backend.md 2.2.2 单端互踢）。</p>
 */
@Component
public class TokenRotationStore {

    private static final int MAX_ENTRIES = 100_000;

    /** jti -> 有效截止（epoch 秒）；Long.MAX_VALUE = 已吊销 */
    private final Map<String, Long> rotated = new ConcurrentHashMap<>();

    /** 标记旧 jti 进入宽限期。 */
    public void markRotated(String jti, long graceSeconds) {
        rotated.put(jti, System.currentTimeMillis() / 1000 + graceSeconds);
    }

    /** 主动吊销（logout）：宽限期后作废语义改为立即作废。 */
    public void revoke(String jti) {
        rotated.put(jti, Long.MAX_VALUE);
    }

    /**
     * jti 是否仍可用于换新：未轮换过 → 可用；宽限期内 → 可用（并发刷新竞态容忍）；
     * 宽限期过 / 已吊销 → 拒绝（调用方转 1003）。
     */
    public boolean isUsable(String jti) {
        Long until = rotated.get(jti);
        if (until == null) {
            return true;
        }
        if (until == Long.MAX_VALUE) {
            return false;
        }
        boolean usable = System.currentTimeMillis() / 1000 <= until;
        if (!usable) {
            rotated.remove(jti); // 过期即清理，防膨胀
        }
        return usable;
    }

    /** 简单防膨胀（P0 单机；P1 Redis TTL 自动过期无需此逻辑）。 */
    void cleanupIfTooLarge() {
        if (rotated.size() <= MAX_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        rotated.entrySet().removeIf(e -> e.getValue() != Long.MAX_VALUE && e.getValue() < now);
    }
}
