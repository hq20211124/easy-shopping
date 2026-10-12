package com.easyshopping.common.web;

import com.easyshopping.common.auth.JwtService;
import com.easyshopping.common.event.DomainEventPublisher;
import com.easyshopping.common.event.InMemoryDomainEventPublisher;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * common 自动装配（无条件 Bean 部分）：雪花 ID、JWT、领域事件发布器。
 * 各服务只要引入 common 依赖即自动具备，无需额外 @Import。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "app.common", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SnowflakeIdGenerator snowflakeIdGenerator(
            @org.springframework.beans.factory.annotation.Value("${app.worker-id:1}") long workerId) {
        return new SnowflakeIdGenerator(workerId);
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtService jwtService(
            @org.springframework.beans.factory.annotation.Value("${app.jwt.secret:dev-only-secret-change-me-in-prod-0123456789abcdef}") String secret,
            @org.springframework.beans.factory.annotation.Value("${app.jwt.access-ttl-seconds:7200}") long accessTtlSeconds,
            @org.springframework.beans.factory.annotation.Value("${app.jwt.refresh-ttl-seconds:604800}") long refreshTtlSeconds) {
        return new JwtService(secret, accessTtlSeconds, refreshTtlSeconds);
    }

    @Bean
    @ConditionalOnMissingBean(DomainEventPublisher.class)
    public InMemoryDomainEventPublisher domainEventPublisher(SnowflakeIdGenerator idGenerator) {
        return new InMemoryDomainEventPublisher(idGenerator);
    }
}
