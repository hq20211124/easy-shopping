package com.admin.security;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理端守卫：/api/admin/** 除登录/刷新外必须携带 ADMIN 角色（网关注入 X-User-Roles）。
 * 买家 Token（roles=[USER]）访问管理端接口一律 403 + 1004。
 */
@Component
public class AdminGuardInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminGuardInterceptor.class);

    static final String ROLE_ADMIN = "ADMIN";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        String path = request.getRequestURI();
        if (isAuthExempt(path)) {
            return true;
        }
        if (!UserContext.roles().contains(ROLE_ADMIN)) {
            log.warn("[admin-guard] deny path={} user={} roles={}", path, UserContext.userId(), UserContext.roles());
            throw new BizException(BizErrorCode.NO_PERMISSION, "需要管理端权限");
        }
        return true;
    }

    private boolean isAuthExempt(String path) {
        return path.startsWith("/api/admin/auth/login")
                || path.startsWith("/api/admin/auth/refresh");
    }
}
