package com.admin.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理端 Refresh Token 轮换记录（P0 进程内内存；TODO(P1) Redis —— 多实例必须）。
 * 语义与 core-user TokenRotationStore 一致：轮换后旧 jti 60s 宽限，logout 立即吊销。
 */
@Component
public class TokenRotationStore {

    private static final int MAX_ENTRIES = 100_000;

    private final Map<String, Long> rotated = new ConcurrentHashMap<>();

    public void markRotated(String jti, long graceSeconds) {
        rotated.put(jti, System.currentTimeMillis() / 1000 + graceSeconds);
    }

    public void revoke(String jti) {
        rotated.put(jti, Long.MAX_VALUE);
    }

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
            rotated.remove(jti);
        }
        return usable;
    }

    void cleanupIfTooLarge() {
        if (rotated.size() <= MAX_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        rotated.entrySet().removeIf(e -> e.getValue() != Long.MAX_VALUE && e.getValue() < now);
    }

    @Value("${app.auth.refresh-grace-seconds:60}")
    public void setGraceSeconds(long graceSeconds) {
        this.graceSeconds = graceSeconds;
    }

    private long graceSeconds = 60;

    public long graceSeconds() {
        return graceSeconds;
    }
}
