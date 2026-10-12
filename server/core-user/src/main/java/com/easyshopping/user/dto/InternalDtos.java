package com.easyshopping.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * 内部端点出入参（仅服务间调用：admin-bff → core-user；未挂网关路由，外网不可达）。
 * 管理端禁止直连其他服务 DB（plans/admin-backend.md 1.1.2），账号唯一事实来源在 core-user。
 */
public final class InternalDtos {

    private InternalDtos() {
    }

    /** POST /api/v1/internal/auth/verify：admin-bff 管理端登录的凭据校验。 */
    public record VerifyRequest(
            @NotBlank String username,
            @NotBlank String password) {
    }

    public record VerifyResponse(
            Long userId,
            String username,
            String nickname,
            List<String> roles,
            Integer status) {
    }

    /** POST /api/v1/internal/users：创建管理端/买家账号（admin-bff 系统管理代理）。 */
    public record UserCreateRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(min = 6, max = 32) String password,
            @Size(max = 64) String nickname,
            @Size(max = 20) String mobile,
            /** CSV，如 ADMIN / USER；空则 USER */
            @Size(max = 64) String roles) {
    }

    /** PUT /api/v1/internal/users/{id}：全部字段可空=不修改。 */
    public record UserUpdateRequest(
            @Size(max = 64) String nickname,
            @Size(max = 20) String mobile,
            @Size(max = 128) String email,
            @Size(min = 6, max = 32) String password,
            Integer gender,
            @Size(max = 10) String birthday,
            Integer status) {
    }

    /** PUT /api/v1/internal/users/{id}/roles */
    public record UserRolesRequest(@NotBlank @Size(max = 64) String roles) {
    }

    /** POST /api/v1/internal/users/{id}/login-fail-reset（解锁/清零失败计数，运营操作） */
    public record LoginFailResetRequest(Integer failCount) {
    }

    public record UserResponse(
            Long userId,
            String username,
            String nickname,
            String mobile,
            String email,
            Integer gender,
            String birthday,
            String avatar,
            Integer status,
            List<String> roles,
            Integer loginFailCount,
            Instant lastLoginAt,
            Instant createdAt) {
    }
}
