package com.easyshopping.gateway.web;

import com.easyshopping.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** 网关健康检查（免登录白名单内）。 */
@RestController
public class GatewayHealthController {

    @Value("${spring.application.name:gateway}")
    private String serviceName;

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "service", serviceName,
                "status", "UP",
                "timestamp", Instant.now().toString()));
    }
}
