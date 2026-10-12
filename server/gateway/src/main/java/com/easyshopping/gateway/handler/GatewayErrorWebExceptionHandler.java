package com.easyshopping.gateway.handler;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.gateway.filter.GatewayLoggingFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 网关统一错误处理：把路由未命中（404）、下游不可达（502/504）、未预期异常
 * 统一渲染为 {@link ApiResponse} 格式（对齐任务要求：统一 401/404 响应格式）。
 */
@Component
@Order(-2)
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }
        HttpStatus status = resolveStatus(ex);
        BizErrorCode code;
        String msg;
        switch (status) {
            case NOT_FOUND -> {
                code = BizErrorCode.NOT_FOUND;
                msg = "接口不存在";
            }
            case BAD_GATEWAY, SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT -> {
                code = BizErrorCode.SYSTEM_ERROR;
                msg = "服务暂不可用";
            }
            default -> {
                code = BizErrorCode.SYSTEM_ERROR;
                msg = "网关内部错误";
            }
        }
        if (status.is5xxServerError() && !(ex instanceof ResponseStatusException)) {
            log.error("[gateway] unhandled error path={}", exchange.getRequest().getURI().getPath(), ex);
        } else {
            log.warn("[gateway] error status={} path={} cause={}", status.value(),
                    exchange.getRequest().getURI().getPath(), ex.getMessage());
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String traceId = exchange.getRequest().getHeaders().getFirst(GatewayLoggingFilter.HEADER_TRACE_ID);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(new ApiResponse<>(code.getCode(), msg, null, traceId));
        } catch (Exception e) {
            body = ("{\"code\":" + code.getCode() + ",\"msg\":\"" + msg + "\"}").getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        response.getHeaders().set(HttpHeaders.CONTENT_LENGTH, String.valueOf(body.length));
        return response.writeWith(Mono.just(buffer));
    }

    private HttpStatus resolveStatus(Throwable ex) {
        if (ex instanceof ResponseStatusException rse && rse.getStatusCode() instanceof HttpStatus hs) {
            return hs;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
