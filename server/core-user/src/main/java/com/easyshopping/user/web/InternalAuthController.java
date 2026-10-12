package com.easyshopping.user.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.idempotent.Idempotent;
import com.easyshopping.user.dto.InternalDtos.LoginFailResetRequest;
import com.easyshopping.user.dto.InternalDtos.UserCreateRequest;
import com.easyshopping.user.dto.InternalDtos.UserResponse;
import com.easyshopping.user.dto.InternalDtos.UserRolesRequest;
import com.easyshopping.user.dto.InternalDtos.UserUpdateRequest;
import com.easyshopping.user.dto.InternalDtos.VerifyRequest;
import com.easyshopping.user.dto.InternalDtos.VerifyResponse;
import com.easyshopping.user.service.AuthService;
import com.easyshopping.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 服务间内部端点（仅 admin-bff 经内网调用）。
 *
 * <p>未挂 gateway 路由（gateway/application.yml 无 /api/v1/internal/**），外网不可达；
 * 管理端禁止直连本服务 DB（plans/admin-backend.md 1.1.2），账号读写统一走这里。</p>
 */
@RestController
public class InternalAuthController {

    private final AuthService authService;
    private final UserService userService;

    public InternalAuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    /**
     * POST /api/v1/internal/auth/verify：admin-bff 管理端登录的凭据校验。
     * 复用登录安全策略（失败计数/锁定/停用），但不签发 Token（Token 由 admin-bff 自签，audience P1 区分）。
     */
    @PostMapping("/api/v1/internal/auth/verify")
    public ApiResponse<VerifyResponse> verify(@Valid @RequestBody VerifyRequest request) {
        var user = authService.verifyCredentials(request.username(), request.password());
        return ApiResponse.ok(new VerifyResponse(user.getId(), user.getUsername(), user.getNickname(),
                AuthService.parseRoles(user.getRoles()), user.getStatus()));
    }

    /* -------------------- 账号管理（admin-bff 系统管理代理） -------------------- */

    @GetMapping("/api/v1/internal/users")
    public ApiResponse<PageResult<UserResponse>> list(@RequestParam(required = false) String role,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(defaultValue = "1") int pageNum,
                                                      @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(userService.internalList(role, keyword, pageNum, pageSize));
    }

    @GetMapping("/api/v1/internal/users/{id}")
    public ApiResponse<UserResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(userService.internalGet(id));
    }

    @PostMapping("/api/v1/internal/users")
    @Idempotent
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody UserCreateRequest request) {
        Long userId = userService.internalCreate(request);
        return ApiResponse.ok(Map.of("userId", userId));
    }

    @PutMapping("/api/v1/internal/users/{id}")
    @Idempotent
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @Valid @RequestBody UserUpdateRequest request) {
        return ApiResponse.ok(Map.of("success", userService.internalUpdate(id, request)));
    }

    /** 软删 + 发 user.deleted 事件（PIPL 注销合规，architecture/04-messaging §7.1）。 */
    @DeleteMapping("/api/v1/internal/users/{id}")
    @Idempotent
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("success", userService.internalDelete(id)));
    }

    @PutMapping("/api/v1/internal/users/{id}/roles")
    @Idempotent
    public ApiResponse<Map<String, Object>> updateRoles(@PathVariable Long id,
                                                        @Valid @RequestBody UserRolesRequest request) {
        return ApiResponse.ok(Map.of("success", userService.internalUpdateRoles(id, request.roles())));
    }

    @PostMapping("/api/v1/internal/users/{id}/login-fail-reset")
    @Idempotent
    public ApiResponse<Map<String, Object>> resetLoginFail(@PathVariable Long id,
                                                           @RequestBody(required = false) LoginFailResetRequest request) {
        return ApiResponse.ok(Map.of("success", userService.internalResetLoginFail(id)));
    }
}
