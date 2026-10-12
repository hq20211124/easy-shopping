package com.easyshopping.common.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.List;

/**
 * 登录上下文：网关 JWT 过滤器校验通过后透传 {@code X-User-Id} / {@code X-User-Roles} 头，
 * 业务服务直接从请求头读取（P0 信任内网直连；P1 起服务间调用也必须走网关或 mTLS）。
 */
public final class UserContext {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ROLES = "X-User-Roles";

    private UserContext() {
    }

    /** 当前用户 ID，未登录返回 null。 */
    public static Long userId() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String v = request.getHeader(HEADER_USER_ID);
        if (v == null || v.isBlank()) {
            return null;
        }
        return Long.parseLong(v);
    }

    /** 当前用户角色列表（网关以逗号拼接），未登录返回空列表。 */
    public static List<String> roles() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return List.of();
        }
        String v = request.getHeader(HEADER_USER_ROLES);
        if (v == null || v.isBlank()) {
            return List.of();
        }
        return Arrays.asList(v.split(","));
    }

    /** 必须登录的接口用：取不到用户 ID 抛 1002。 */
    public static long requireUserId() {
        Long id = userId();
        if (id == null) {
            throw new BizException(BizErrorCode.NOT_LOGIN);
        }
        return id;
    }

    /**
     * 管理端角色判定（跨模块契约对齐：三种历史写法等价）。
     *
     * <p>{@code ADMIN}（core-user JWT / admin-bff 签发）、{@code ROLE_ADMIN}（Spring 约定，
     * core-product/core-price/core-order 早期写法）、{@code admin}（core-promotion 早期写法）
     * 均视为管理员；买家 {@code USER} 不匹配。</p>
     */
    public static boolean isAdmin() {
        for (String role : roles()) {
            if ("ADMIN".equalsIgnoreCase(role) || "ROLE_ADMIN".equalsIgnoreCase(role)) {
                return true;
            }
        }
        return false;
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
