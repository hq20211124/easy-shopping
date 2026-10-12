package com.price.web;

import com.easyshopping.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/** 健康检查（网关白名单内，免登录）。 */
@RestController
public class HealthController {

    @Value("${spring.application.name}")
    private String serviceName;

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "service", serviceName,
                "status", "UP",
                "timestamp", Instant.now().toString()));
    }
}