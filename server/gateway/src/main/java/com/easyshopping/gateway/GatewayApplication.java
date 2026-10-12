package com.easyshopping.gateway;

import com.easyshopping.gateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 统一网关入口（:8080）。
 *
 * <p>P0 用 Spring Cloud Gateway 静态路由（application.yml，无 Nacos）；
 * 按 plans/admin-backend.md 1.1.2 v1.3 裁决（C5/C12），生产网关本体统一选型 Higress/APISIX，
 * 本模块的路由/JWT/限流逻辑届时下沉为网关插件，业务代码不迁移。</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
