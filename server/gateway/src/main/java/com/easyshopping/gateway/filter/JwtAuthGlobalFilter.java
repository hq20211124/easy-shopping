package com.easyshopping.gateway.filter;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.auth.JwtClaims;
import com.easyshopping.common.auth.JwtService;
import com.easyshopping.gateway.config.GatewayProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT 鉴权全局过滤器：
 * <ol>
 *   <li>白名单路径直接放行（登录/验证码/商品浏览等）；</li>
 *   <li>其余路径校验 {@code Authorization: Bearer <token>}（access token），失败统一 401 + 统一响应格式；</li>
 *   <li>校验通过后向下游透传 {@code X-User-Id} / {@code X-User-Roles}（先移除客户端伪造的同名头）。</li>
 * </ol>
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthGlobalFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JwtAuthGlobalFilter(JwtService jwtService, GatewayProperties properties) {
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (isWhitelisted(path)) {
            return chain.filter(exchange);
        }

        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return writeUnauthorized(exchange, BizErrorCode.NOT_LOGIN);
        }

        JwtClaims claims;
        try {
            claims = jwtService.parse(authorization.substring(BEARER_PREFIX.length()));
            if (!claims.isAccessToken()) {
                return writeUnauthorized(exchange, BizErrorCode.TOKEN_EXPIRED);
            }
        } catch (Exception e) {
            log.warn("jwt auth failed path={} reason={}", path, e.getMessage());
            return writeUnauthorized(exchange, BizErrorCode.TOKEN_EXPIRED);
        }

        final JwtClaims valid = claims;
        ServerHttpRequest mutated = request.mutate()
                .headers(headers -> {
                    // 防伪造：先删除客户端可能自带的用户头，再由网关注入
                    headers.remove(UserHeaders.USER_ID);
                    headers.remove(UserHeaders.USER_ROLES);
                    headers.set(UserHeaders.USER_ID, String.valueOf(valid.userId()));
                    headers.set(UserHeaders.USER_ROLES, String.join(",", valid.roles()));
                })
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private boolean isWhitelisted(String path) {
        GatewayProperties.Auth auth = properties.auth();
        if (auth == null || auth.whitelist() == null) {
            return false;
        }
        return auth.whitelist().stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange, BizErrorCode code) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(ApiResponse.error(code.getCode(), code.getMsg()));
        } catch (JsonProcessingException e) {
            body = ("{\"code\":" + code.getCode() + ",\"msg\":\"" + code.getMsg() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 限流先于鉴权执行（限流过滤器 order 更小），避免未认证流量打满校验开销
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    /** 下游用户头常量（与 common UserContext 对应）。 */
    public static final class UserHeaders {
        public static final String USER_ID = "X-User-Id";
        public static final String USER_ROLES = "X-User-Roles";

        private UserHeaders() {
        }
    }

    /** 白名单缺省值（yml 未配置时兜底，见 application.yml）。 */
    public static final List<String> DEFAULT_WHITELIST = List.of(
            "/health", "/actuator/health",
            "/api/v1/auth/login/**", "/api/v1/auth/sms-code", "/api/v1/auth/register", "/api/v1/auth/refresh",
            "/api/v1/products/**", "/api/v1/category/**", "/api/v1/search/**",
            "/api/admin/auth/login", "/api/admin/auth/refresh");
}
