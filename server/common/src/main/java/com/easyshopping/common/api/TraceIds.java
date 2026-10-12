package com.easyshopping.common.api;

import java.util.UUID;

/**
 * traceId 简易持有器：网关生成（或透传 X-Trace-Id 头）→ 服务端拦截器按请求线程设置。
 * P0 线程级实现；P1 接入 Micrometer Tracing / SkyWalking 后由链路追踪替代。
 */
public final class TraceIds {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    private TraceIds() {
    }

    public static String get() {
        String id = HOLDER.get();
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            HOLDER.set(id);
        }
        return id;
    }

    public static void set(String traceId) {
        HOLDER.set(traceId);
    }

    public static void clear() {
        HOLDER.remove();
    }
}
