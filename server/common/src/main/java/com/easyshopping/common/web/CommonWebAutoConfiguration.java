package com.easyshopping.common.web;

import com.easyshopping.common.api.GlobalExceptionHandler;
import com.easyshopping.common.idempotent.IdempotentInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * common 自动装配（Servlet MVC 侧）：全局异常处理、traceId 透传、幂等拦截器。
 * gateway 是 WebFlux 应用，本配置不会生效（互不干扰）。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnProperty(prefix = "app.common.web", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CommonWebAutoConfiguration {

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    public IdempotentInterceptor idempotentInterceptor() {
        return new IdempotentInterceptor();
    }

    @Bean
    public WebMvcConfigurer commonWebMvcConfigurer(IdempotentInterceptor idempotentInterceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new TraceIdInterceptor()).addPathPatterns("/**");
                registry.addInterceptor(idempotentInterceptor).addPathPatterns("/**");
            }
        };
    }
}
