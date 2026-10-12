package com.admin.auth;

import jakarta.validation.constraints.NotBlank;

/** 管理端认证出入参（对齐 web-admin src/api/auth.ts）。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** POST /api/admin/auth/login（captchaId/captchaCode：失败 ≥3 次后强制，plans 2.5.1） */
    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password,
            String captchaId,
            String captchaCode) {
    }

    /** POST /api/admin/auth/refresh（P0 允许 body 传 refreshToken，便于非浏览器客户端/联调） */
    public record RefreshRequest(String refreshToken) {
    }

    /** POST /api/admin/auth/logout */
    public record LogoutRequest(String refreshToken) {
    }

    /** 登录响应（web-admin LoginResult：accessToken + expiresIn + tokenType；refresh 走 httpOnly Cookie） */
    public record LoginResult(String accessToken, long expiresIn, String tokenType, String refreshToken) {
    }

    /** GET /api/admin/auth/info（web-admin AuthProfile） */
    public record AuthProfile(
            Long userId,
            String username,
            String nickname,
            java.util.List<String> roles,
            java.util.List<String> permissions,
            Integer dataScope,
            Long permVer,
            Long deptId,
            String deptName) {
    }
}
