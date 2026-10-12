package com.easyshopping.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 请求日志 + traceId 透传：入口生成/透传 X-Trace-Id，记录 方法/路径/状态码/耗时/用户。
 */
@Component
public class GatewayLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayLoggingFilter.class);
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String traceId = request.getHeaders().getFirst(HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        final String tid = traceId;
        final long start = System.currentTimeMillis();
        ServerHttpRequest mutated = request.mutate()
                .headers(h -> h.set(HEADER_TRACE_ID, tid))
                .build();
        return chain.filter(exchange.mutate().request(mutated).build())
                .doFinally(signal -> log.info("[gateway] traceId={} {} {} -> {} {}ms user={}",
                        tid,
                        mutated.getMethod(),
                        mutated.getURI().getPath(),
                        exchange.getResponse().getStatusCode(),
                        System.currentTimeMillis() - start,
                        mutated.getHeaders().getFirst(JwtAuthGlobalFilter.UserHeaders.USER_ID)));
    }

    @Override
    public int getOrder() {
        // 最后执行（响应最先返回），保证日志能看到最终状态码
        return Ordered.LOWEST_PRECEDENCE;
    }
}
