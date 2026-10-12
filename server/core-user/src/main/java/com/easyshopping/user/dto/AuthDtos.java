package com.easyshopping.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 认证相关出入参（plans/admin-backend.md 2.5.1 / plans/android-backend.md 2.1.2）。 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** POST /api/v1/auth/login（P0：用户名+密码；手机号+验证码登录 P1，见 README） */
    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }

    /** POST /api/v1/auth/register（手机号+验证码；P0 验证码固定 123456，见 AuthService） */
    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String mobile,
            @NotBlank(message = "验证码不能为空") String code,
            @NotBlank @Size(min = 6, max = 32, message = "密码长度 6~32 位") String password) {
    }

    /** POST /api/v1/auth/refresh */
    public record RefreshRequest(@NotBlank(message = "refreshToken 不能为空") String refreshToken) {
    }

    /** POST /api/v1/auth/logout（refreshToken 可空：丢失时也允许静默登出） */
    public record LogoutRequest(String refreshToken) {
    }

    /**
     * 登录/刷新统一返回体。字段对齐 plans/android-backend.md 2.1.3（userId/accessToken/refreshToken/expiresIn），
     * iOS snake_case 差异由 P1 BFF 按端适配（骨架统一 camelCase，见 DEV.md 3 响应信封差异）。
     */
    public record TokenResponse(
            Long userId,
            String username,
            String nickname,
            java.util.List<String> roles,
            String accessToken,
            String refreshToken,
            long expiresIn,
            String tokenType,
            Boolean isNewUser) {

        public static TokenResponse of(Long userId, String username, String nickname,
                                       java.util.List<String> roles, String accessToken,
                                       String refreshToken, long expiresIn, boolean isNewUser) {
            return new TokenResponse(userId, username, nickname, roles, accessToken,
                    refreshToken, expiresIn, "Bearer", isNewUser);
        }
    }
}
