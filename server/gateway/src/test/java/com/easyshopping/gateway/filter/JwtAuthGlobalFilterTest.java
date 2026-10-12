package com.easyshopping.gateway.filter;

import com.easyshopping.common.auth.JwtService;
import com.easyshopping.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtAuthGlobalFilterTest {

    private final JwtService jwtService =
            new JwtService("gateway-test-secret-0123456789abcdef-0123456789", 7200, 604800);

    private final JwtAuthGlobalFilter filter = new JwtAuthGlobalFilter(jwtService, new GatewayProperties(
            new GatewayProperties.Auth(List.of("/api/v1/products/**", "/api/v1/auth/login/**")),
            new GatewayProperties.RateLimit(0)));

    private final AtomicReference<ServerWebExchange> passed = new AtomicReference<>();
    private final GatewayFilterChain chain = exchange -> {
        passed.set(exchange);
        return Mono.empty();
    };

    @Test
    void whitelistedPathPassesWithoutToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/products/1").build());
        filter.filter(exchange, chain).block();
        assertEquals(exchange, passed.get());
        assertNull(exchange.getRequest().getHeaders().getFirst(JwtAuthGlobalFilter.UserHeaders.USER_ID));
    }

    @Test
    void missingTokenReturns401WithCode1002() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/orders").build());
        filter.filter(exchange, chain).block();
        assertNull(passed.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        String body = exchange.getResponse().getBodyAsString().block();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"code\":1002"), body);
    }

    @Test
    void validTokenInjectsUserHeadersAndStripsSpoofedOnes() {
        String token = jwtService.generateAccessToken(10086L, List.of("ROLE_BUYER"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(JwtAuthGlobalFilter.UserHeaders.USER_ID, "999") // 伪造头必须被覆盖
                        .build());
        filter.filter(exchange, chain).block();
        ServerWebExchange passedExchange = passed.get();
        assertEquals("10086", passedExchange.getRequest().getHeaders()
                .getFirst(JwtAuthGlobalFilter.UserHeaders.USER_ID));
        assertEquals("ROLE_BUYER", passedExchange.getRequest().getHeaders()
                .getFirst(JwtAuthGlobalFilter.UserHeaders.USER_ROLES));
    }

    @Test
    void expiredOrTamperedTokenReturns401WithCode1003() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer aaa.bbb.ccc")
                        .build());
        filter.filter(exchange, chain).block();
        assertNull(passed.get());
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        String body = exchange.getResponse().getBodyAsString().block();
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("\"code\":1003"), body);
    }
}
