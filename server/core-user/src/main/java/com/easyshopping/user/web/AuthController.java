package com.easyshopping.user.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.UserContext;
import com.easyshopping.common.idempotent.Idempotent;
import com.easyshopping.user.dto.AuthDtos.LoginRequest;
import com.easyshopping.user.dto.AuthDtos.LogoutRequest;
import com.easyshopping.user.dto.AuthDtos.RefreshRequest;
import com.easyshopping.user.dto.AuthDtos.RegisterRequest;
import com.easyshopping.user.dto.AuthDtos.TokenResponse;
import com.easyshopping.user.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 买家端认证接口（:8081；网关白名单：login / refresh / register，见 gateway/application.yml）。
 *
 * <p>与管理端 P0 共用本服务的账号体系：JWT roles = USER（买家）/ ADMIN（管理端），
 * TODO(P1) 拆 admin-user 服务（plans/admin-backend.md 1.1.3）。</p>
 */
@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** POST /api/v1/auth/login：用户名+密码（失败 5 次锁 15 分钟；密码不落日志）。 */
    @PostMapping("/api/v1/auth/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.username(), request.password()));
    }

    /** POST /api/v1/auth/register：手机号+验证码（P0 验证码固定 123456）；发 member.registered 事件。 */
    @PostMapping("/api/v1/auth/register")
    @Idempotent
    public ApiResponse<TokenResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    /** POST /api/v1/auth/refresh：轮换换新；旧 refresh token 60s 宽限（P1 Redis，见 TokenRotationStore）。 */
    @PostMapping("/api/v1/auth/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    /** POST /api/v1/auth/logout：吊销 refresh token（access token 依赖短 TTL 自然失效，P1 jti 黑名单）。 */
    @PostMapping("/api/v1/auth/logout")
    public ApiResponse<Map<String, Object>> logout(@RequestBody(required = false) LogoutRequest request) {
        String refreshToken = request == null ? null : request.refreshToken();
        authService.logout(refreshToken);
        return ApiResponse.ok(Map.of("success", true));
    }

    /** GET /api/v1/auth/me：当前登录用户 ID（联调自检用；无业务逻辑）。 */
    @org.springframework.web.bind.annotation.GetMapping("/api/v1/auth/me")
    public ApiResponse<Map<String, Object>> me() {
        return ApiResponse.ok(Map.of("userId", UserContext.requireUserId()));
    }
}
