package com.admin.web;

import com.admin.auth.AdminAuthService;
import com.admin.auth.AuthDtos.LoginRequest;
import com.admin.auth.AuthDtos.LoginResult;
import com.admin.auth.AuthDtos.LogoutRequest;
import com.admin.auth.AuthDtos.RefreshRequest;
import com.admin.auth.CaptchaService;
import com.admin.rbac.RbacService;
import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.UserContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * 管理端认证（web-admin 契约：src/api/auth.ts，plans/admin-backend.md 2.5.1）。
 *
 * <ul>
 *   <li>POST /api/admin/auth/login —— accessToken 进 body；refreshToken 进 httpOnly Cookie（2.1.2 前端约定）</li>
 *   <li>POST /api/admin/auth/refresh —— 从 Cookie 读 refresh，轮换后重发 Cookie</li>
 *   <li>POST /api/admin/auth/logout —— 吊销 + 清 Cookie</li>
 *   <li>GET  /api/admin/auth/info —— 角色/权限点/dataScope/permVer（AuthProfile 结构）</li>
 *   <li>GET  /api/admin/auth/menus —— 按权限过滤的菜单树（PermissionMenuItem 结构）</li>
 *   <li>GET  /api/admin/auth/captcha —— SVG 验证码（需 Token，见 CaptchaService 注释）</li>
 * </ul>
 */
@RestController
public class AdminAuthController {

    private final AdminAuthService authService;
    private final CaptchaService captchaService;
    private final RbacService rbacService;
    private final String cookieName;
    private final String cookiePath;

    public AdminAuthController(AdminAuthService authService,
                               CaptchaService captchaService,
                               RbacService rbacService,
                               @Value("${app.auth.refresh-cookie-name:ADMIN_RT}") String cookieName,
                               @Value("${app.auth.refresh-cookie-path:/api/admin}") String cookiePath) {
        this.authService = authService;
        this.captchaService = captchaService;
        this.rbacService = rbacService;
        this.cookieName = cookieName;
        this.cookiePath = cookiePath;
    }

    @PostMapping("/api/admin/auth/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        LoginResult result = authService.login(request.username(), request.password(),
                request.captchaId(), request.captchaCode());
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken(),
                authService.refreshTtlSeconds()).toString());
        return ResponseEntity.ok(ApiResponse.ok(toBody(result)));
    }

    @PostMapping("/api/admin/auth/refresh")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refresh(
            @RequestBody(required = false) RefreshRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        String token = extractRefreshToken(request == null ? null : request.refreshToken(), httpRequest);
        LoginResult result = authService.refresh(token);
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken(),
                authService.refreshTtlSeconds()).toString());
        return ResponseEntity.ok(ApiResponse.ok(toBody(result)));
    }

    @PostMapping("/api/admin/auth/logout")
    public ApiResponse<Map<String, Object>> logout(
            @RequestBody(required = false) LogoutRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        String token = extractRefreshToken(request == null ? null : request.refreshToken(), httpRequest);
        authService.logout(token);
        ResponseCookie cleared = ResponseCookie.from(cookieName, "")
                .path(cookiePath).httpOnly(true).sameSite("Strict").maxAge(Duration.ZERO).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cleared.toString());
        return ApiResponse.ok(Map.of("success", true));
    }

    /** 当前用户信息（角色/权限点/dataScope/permVer，web-admin AuthProfile）。 */
    @GetMapping("/api/admin/auth/info")
    public ApiResponse<com.admin.auth.AuthDtos.AuthProfile> info() {
        return ApiResponse.ok(authService.info(UserContext.requireUserId()));
    }

    /** 按权限过滤的菜单树（动态路由数据源，web-admin PermissionMenuItem 结构）。 */
    @GetMapping("/api/admin/auth/menus")
    public ApiResponse<java.util.List<RbacService.MenuNode>> menus() {
        return ApiResponse.ok(authService.menus(UserContext.requireUserId()));
    }

    @GetMapping("/api/admin/auth/captcha")
    public ApiResponse<CaptchaService.Captcha> captcha() {
        return ApiResponse.ok(captchaService.generate());
    }

    private Map<String, Object> toBody(LoginResult result) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("accessToken", result.accessToken());
        body.put("expiresIn", result.expiresIn());
        body.put("tokenType", result.tokenType());
        return body;
    }

    /** Refresh Token 只走 httpOnly Cookie（2.1.2：禁止 JS 可读存储）；body 仅为联调兜底。 */
    private String extractRefreshToken(String fromBody, HttpServletRequest request) {
        if (request != null && request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (cookieName.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        return fromBody;
    }

    private ResponseCookie refreshCookie(String token, long maxAgeSeconds) {
        return ResponseCookie.from(cookieName, token)
                .path(cookiePath)
                .httpOnly(true)
                .sameSite("Strict")
                // .secure(true)  // 生产 HTTPS 必开；本地开发 http 需关闭（TODO(P1) profile 区分）
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build();
    }
}
