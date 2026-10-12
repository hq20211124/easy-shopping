package com.easyshopping.gateway.filter;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.gateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单限流（P0 进程内令牌桶，按客户端 IP 维度）。
 *
 * <p>生产口径：限流规则同源下发（Sentinel + 网关插件双侧生效，plans/admin-backend.md 11.4），
 * P1 替换本实现；限流返回 HTTP 429 + 业务码 1001（plans/android-backend.md 16.2 约定 1001 含限流）。</p>
 */
@Component
public class GatewayRateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayRateLimitFilter.class);

    private final GatewayProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public GatewayRateLimitFilter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        GatewayProperties.RateLimit limit = properties.rateLimit();
        double permitsPerSecond = limit == null ? 0 : limit.permitsPerSecond();
        if (permitsPerSecond <= 0) {
            return chain.filter(exchange);
        }
        String clientIp = resolveClientIp(exchange);
        if (!tryAcquire(clientIp, permitsPerSecond)) {
            log.warn("rate limited ip={} path={}", clientIp, exchange.getRequest().getURI().getPath());
            return writeTooManyRequests(exchange);
        }
        return chain.filter(exchange);
    }

    /**
     * 令牌桶：P0 用粗粒度同步（单机开发/小流量够用），大流量场景由 P1 Sentinel 替代。
     */
    private synchronized boolean tryAcquire(String clientIp, double permitsPerSecond) {
        long now = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(clientIp, k -> new Bucket(permitsPerSecond, now));
        double elapsedSeconds = (now - bucket.lastRefillNanos) / 1e9;
        bucket.tokens = Math.min(permitsPerSecond, bucket.tokens + elapsedSeconds * permitsPerSecond);
        bucket.lastRefillNanos = now;
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0;
            return true;
        }
        return false;
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        String xff = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null ? "unknown" : remote.getAddress() == null ? remote.getHostString() : remote.getAddress().getHostAddress();
    }

    private Mono<Void> writeTooManyRequests(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatusCode.valueOf(429));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(
                    ApiResponse.error(BizErrorCode.PARAM_ERROR.getCode(), "请求过于频繁，请稍后重试"));
        } catch (Exception e) {
            body = "{\"code\":1001,\"msg\":\"请求过于频繁\"}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 限流在鉴权之前执行
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(double tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }
}
