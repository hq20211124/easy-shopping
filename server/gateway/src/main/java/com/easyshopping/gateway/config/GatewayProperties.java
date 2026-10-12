package com.easyshopping.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 网关配置（application.yml 的 {@code gateway.*}）。
 *
 * @param auth        鉴权配置（免登录白名单）
 * @param rateLimit   限流配置（P0 进程内令牌桶；P1 接 Sentinel/网关插件，规则同源）
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(Auth auth, RateLimit rateLimit) {

    /**
     * @param whitelist 免登录路径（Ant 风格），命中即放行且不注入用户头
     */
    public record Auth(List<String> whitelist) {
    }

    /**
     * @param permitsPerSecond 单 IP QPS 上限，&le;0 表示关闭限流
     */
    public record RateLimit(double permitsPerSecond) {
    }
}
