package com.admin.config;

import com.admin.security.AdminGuardInterceptor;
import com.easyshopping.common.idempotent.IdempotentInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web 配置：管理端角色守卫拦截器（权限点细粒度由 @RequirePerm 切面负责）+ 写接口幂等拦截器。 */
@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    private final AdminGuardInterceptor adminGuardInterceptor;

    public AdminWebConfig(AdminGuardInterceptor adminGuardInterceptor) {
        this.adminGuardInterceptor = adminGuardInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminGuardInterceptor).addPathPatterns("/api/admin/**");
        // 所有写接口（POST/PUT/DELETE）须携带 Idempotency-Key，见 plans/admin-backend.md 10.1.1
        registry.addInterceptor(new IdempotentInterceptor()).addPathPatterns("/api/admin/**");
    }
}
