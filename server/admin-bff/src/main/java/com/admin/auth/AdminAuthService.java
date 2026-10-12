package com.admin.auth;

import com.admin.client.ClientDtos.InternalUserDto;
import com.admin.client.ClientDtos.VerifyResult;
import com.admin.client.CoreUserClient;
import com.admin.auth.AuthDtos.LoginResult;
import com.admin.rbac.RbacService;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.auth.InvalidTokenException;
import com.easyshopping.common.auth.JwtClaims;
import com.easyshopping.common.auth.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 管理端认证（plans/admin-backend.md 2.1/2.5.1）。
 *
 * <p>登录经 HTTP 调 core-user {@code POST /api/v1/internal/auth/verify} 校验凭据
 * （禁止直查 core-user DB —— 1.1.2）；Token 由本服务自签，JWT roles=[ADMIN]，
 * 与买家端（roles=[USER]）同库区分。TODO(P1)：JWT audience 区分（android-backend 2.2.2
 * aud=easy-app/easy-admin）+ 单端互踢 sessionId + 权限变更 permVer 实时比对（2.1.4）。</p>
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    private final CoreUserClient coreUserClient;
    private final JwtService jwtService;
    private final RbacService rbacService;
    private final CaptchaService captchaService;
    private final TokenRotationStore rotationStore;
    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;

    public AdminAuthService(CoreUserClient coreUserClient,
                            JwtService jwtService,
                            RbacService rbacService,
                            CaptchaService captchaService,
                            TokenRotationStore rotationStore,
                            @Value("${app.jwt.access-ttl-seconds:7200}") long accessTtlSeconds,
                            @Value("${app.jwt.refresh-ttl-seconds:604800}") long refreshTtlSeconds) {
        this.coreUserClient = coreUserClient;
        this.jwtService = jwtService;
        this.rbacService = rbacService;
        this.captchaService = captchaService;
        this.rotationStore = rotationStore;
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    /**
     * 登录：验证码（若携带）→ core-user 凭据校验（失败计数/锁定在其侧）→ ADMIN 角色校验 → 签发双 Token。
     * 失败 3 次后前端强制携带验证码；P0 服务端不强制（TODO P1）。
     */
    public LoginResult login(String username, String password, String captchaId, String captchaCode) {
        captchaService.validate(captchaId, captchaCode);
        VerifyResult verify = coreUserClient.verify(username, password);
        List<String> roles = verify.roles() == null ? List.of() : verify.roles();
        if (!roles.contains("ADMIN")) {
            log.warn("[admin-auth] non-admin login denied userId={} roles={}", verify.userId(), roles);
            throw new BizException(BizErrorCode.NO_PERMISSION, "该账号无管理端权限");
        }
        rbacService.ensureUserExt(verify.userId(), null);
        // 集成引导：首次登录的管理员若未绑定任何 RBAC 角色，自动绑定内置 super_admin（否则登录后无任何权限）
        rbacService.ensureBootstrapRole(verify.userId());
        log.info("[admin-auth] login userId={}", verify.userId());
        return issueTokens(verify.userId());
    }

    /** Refresh：轮换（旧 token 60s 宽限）+ 重签 access。P0 不回源校验账号状态（TODO P1 同步禁用态）。 */
    public LoginResult refresh(String refreshToken) {
        JwtClaims claims = parseRefreshToken(refreshToken);
        rotationStore.cleanupIfTooLarge();
        if (!rotationStore.isUsable(claims.jti())) {
            throw new BizException(BizErrorCode.TOKEN_EXPIRED, "Refresh Token 已失效，请重新登录");
        }
        rotationStore.markRotated(claims.jti(), rotationStore.graceSeconds());
        return issueTokens(claims.userId());
    }

    /** 登出：吊销 Refresh Token（access 依赖短 TTL；P1 jti 黑名单）。 */
    public boolean logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                JwtClaims claims = jwtService.parse(refreshToken);
                if (JwtService.TYPE_REFRESH.equals(claims.tokenType())) {
                    rotationStore.revoke(claims.jti());
                }
            } catch (InvalidTokenException e) {
                log.warn("[admin-auth] logout with invalid refresh token: {}", e.getMessage());
            }
        }
        return true;
    }

    /** GET /api/admin/auth/info：账号信息（core-user）+ RBAC（本地）。 */
    public AuthDtos.AuthProfile info(Long userId) {
        InternalUserDto user = coreUserClient.getUser(userId);
        return new AuthDtos.AuthProfile(
                userId,
                user.username(),
                user.nickname(),
                rbacService.roleCodes(userId),
                List.copyOf(rbacService.permCodes(userId)),
                rbacService.dataScope(userId).code(),
                rbacService.permVer(userId),
                rbacService.deptId(userId),
                rbacService.deptName(rbacService.deptId(userId)));
    }

    public List<com.admin.rbac.RbacService.MenuNode> menus(Long userId) {
        return rbacService.menus(userId);
    }

    public long refreshTtlSeconds() {
        return refreshTtlSeconds;
    }

    private LoginResult issueTokens(long userId) {
        String access = jwtService.generateAccessToken(userId, List.of("ADMIN"));
        String refresh = jwtService.generateRefreshToken(userId);
        return new LoginResult(access, accessTtlSeconds, "Bearer", refresh);
    }

    private JwtClaims parseRefreshToken(String token) {
        JwtClaims claims;
        try {
            claims = jwtService.parse(token);
        } catch (InvalidTokenException e) {
            throw new BizException(BizErrorCode.TOKEN_EXPIRED, "Refresh Token 无效或已过期");
        }
        if (!JwtService.TYPE_REFRESH.equals(claims.tokenType())) {
            throw new BizException(BizErrorCode.TOKEN_EXPIRED, "Token 类型错误");
        }
        return claims;
    }
}
