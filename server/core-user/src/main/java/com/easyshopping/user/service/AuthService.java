package com.easyshopping.user.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.auth.InvalidTokenException;
import com.easyshopping.common.auth.JwtClaims;
import com.easyshopping.common.auth.JwtService;
import com.easyshopping.common.event.DomainEventNames;
import com.easyshopping.common.event.DomainEventPublisher;
import com.easyshopping.user.domain.UserAccount;
import com.easyshopping.user.dto.AuthDtos.RegisterRequest;
import com.easyshopping.user.dto.AuthDtos.TokenResponse;
import com.easyshopping.user.repo.UserAccountRepository;
import com.easyshopping.user.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * 认证服务：登录 / 注册 / refresh 轮换 / 登出（plans/admin-backend.md 2.1、2.5.1；买家端 plans/android-backend.md 2.1）。
 *
 * <p>安全约束：<b>密码（明文与哈希）绝不写日志</b>；登录失败计数与锁定落库
 * （5 次失败锁 15 分钟，plans/admin-backend.md 2.5.1）。</p>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    static final int MAX_LOGIN_FAILS = 5;
    static final int LOCK_MINUTES = 15;
    /** P0 短信通道未接入：注册验证码固定 123456。TODO(P1) 接短信服务商 + Redis 验证码（5 分钟 TTL、3 次/日频控），见 README */
    static final String P0_FIXED_SMS_CODE = "123456";

    private final UserAccountRepository userRepo;
    private final JwtService jwtService;
    private final DomainEventPublisher eventPublisher;
    private final TokenRotationStore rotationStore;
    private final LoginAuditService loginAudit;
    private final long accessTtlSeconds;
    private final long refreshGraceSeconds;

    public AuthService(UserAccountRepository userRepo,
                       JwtService jwtService,
                       DomainEventPublisher eventPublisher,
                       TokenRotationStore rotationStore,
                       LoginAuditService loginAudit,
                       @Value("${app.jwt.access-ttl-seconds:7200}") long accessTtlSeconds,
                       @Value("${app.auth.refresh-grace-seconds:60}") long refreshGraceSeconds) {
        this.userRepo = userRepo;
        this.jwtService = jwtService;
        this.eventPublisher = eventPublisher;
        this.rotationStore = rotationStore;
        this.loginAudit = loginAudit;
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshGraceSeconds = refreshGraceSeconds;
    }

    /** 用户名+密码登录（买家与管理端 P0 共用；JWT roles 区分 USER/ADMIN）。 */
    @Transactional
    public TokenResponse login(String username, String password) {
        UserAccount user = verifyCredentials(username, password);
        log.info("[auth] login success userId={} roles={}", user.getId(), user.getRoles());
        return issueTokens(user, false);
    }

    /**
     * 凭据校验（供 login 与 admin-bff 的内部 verify 复用）。
     * 失败即计数，达 5 次锁定 15 分钟；不存在 → 2001；密码错/锁定/停用 → 2002。
     */
    @Transactional
    public UserAccount verifyCredentials(String username, String password) {
        UserAccount user = userRepo.findByUsernameAndDeletedFalse(username)
                .orElseThrow(() -> new BizException(BizErrorCode.USER_NOT_FOUND));
        if (user.isLocked()) {
            throw new BizException(BizErrorCode.PASSWORD_ERROR, "账号已锁定，请 " + LOCK_MINUTES + " 分钟后重试");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(BizErrorCode.PASSWORD_ERROR, "账号已停用，请联系平台");
        }
        if (!PasswordHasher.matches(password, user.getPasswordHash())) {
            // 内存实例仅用于生成响应文案；计数/锁定经独立事务提交（否则随本方法异常回滚丢失）
            user.recordLoginFailure(MAX_LOGIN_FAILS, LOCK_MINUTES);
            loginAudit.recordLoginFailure(user.getId(), MAX_LOGIN_FAILS, LOCK_MINUTES);
            log.warn("[auth] login fail userId={} failCount={} locked={}", user.getId(),
                    user.getLoginFailCount(), user.isLocked());
            throw new BizException(BizErrorCode.PASSWORD_ERROR,
                    user.isLocked() ? "密码错误次数过多，账号已锁定 " + LOCK_MINUTES + " 分钟" : "密码错误");
        }
        // 校验通过后按需升级密码哈希（P0-H：存量 s256 单轮 SHA-256 → PBKDF2-HMAC-SHA256）。
        // rehashIfNeeded 对已是新格式且迭代数达标的用户原样返回，不浪费 CPU。
        user.setPasswordHash(PasswordHasher.rehashIfNeeded(password, user.getPasswordHash()));
        user.recordLoginSuccess();
        loginAudit.recordLoginSuccess(user.getId());
        return user;
    }

    /** 买家注册（手机号+验证码）。P0 验证码固定 123456，TODO(P1) 短信通道。 */
    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (!P0_FIXED_SMS_CODE.equals(request.code())) {
            throw new BizException(BizErrorCode.CAPTCHA_ERROR);
        }
        if (userRepo.existsByUsername(request.mobile())) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "该手机号已注册");
        }
        UserAccount user = new UserAccount(request.mobile(), request.mobile(),
                PasswordHasher.hash(request.password()), "用户" + request.mobile().substring(7), "USER");
        user = userRepo.save(user);
        // 领域事件：注册（本地事务提交后发布；P0 进程内日志分发，P1 RabbitMQ）
        // 事件权威名 member.registered（architecture/04-messaging.md §7.1，简报中的 user.registered 以此为准登记）；
        // 消费者：og-member-mkt（新人礼）、og-member-data（埋点），P1 接入。
        eventPublisher.publish(DomainEventNames.MEMBER_REGISTERED, java.util.Map.of(
                "userId", user.getId(),
                "mobile", user.getMobile(),
                "registerTime", user.getCreatedAt().toString()));
        log.info("[auth] register userId={}", user.getId());
        return issueTokens(user, true);
    }

    /**
     * Refresh Token 轮换：旧 token 进入 60s 宽限期（并发刷新竞态容忍）后作废，签发新 access+refresh。
     * TODO(P1)：宽限/吊销记录迁 Redis（TokenRotationStore 注释）。
     */
    @Transactional(readOnly = true)
    public TokenResponse refresh(String refreshToken) {
        JwtClaims claims = parseRefreshToken(refreshToken);
        rotationStore.cleanupIfTooLarge();
        if (!rotationStore.isUsable(claims.jti())) {
            throw new BizException(BizErrorCode.TOKEN_EXPIRED, "Refresh Token 已失效，请重新登录");
        }
        rotationStore.markRotated(claims.jti(), refreshGraceSeconds);
        UserAccount user = userRepo.findByIdAndDeletedFalse(claims.userId())
                .orElseThrow(() -> new BizException(BizErrorCode.TOKEN_EXPIRED, "用户不存在或已注销"));
        return issueTokens(user, false);
    }

    /**
     * 登出：吊销 Refresh Token（access token 为无状态 JWT，P0 依赖 2h 短 TTL 自然失效；
     * TODO(P1) jti 黑名单落 Redis + 管理端单端互踢 sessionId）。
     */
    public boolean logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            try {
                JwtClaims claims = jwtService.parse(refreshToken);
                if (JwtService.TYPE_REFRESH.equals(claims.tokenType())) {
                    rotationStore.revoke(claims.jti());
                }
            } catch (InvalidTokenException e) {
                // 静默：登出接口对坏 token 幂等成功（客户端清理本地态优先）
                log.warn("[auth] logout with invalid refresh token: {}", e.getMessage());
            }
        }
        return true;
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

    private TokenResponse issueTokens(UserAccount user, boolean isNewUser) {
        List<String> roles = parseRoles(user.getRoles());
        String access = jwtService.generateAccessToken(user.getId(), roles);
        String refresh = jwtService.generateRefreshToken(user.getId());
        return TokenResponse.of(user.getId(), user.getUsername(), user.getNickname(), roles,
                access, refresh, accessTtlSeconds, isNewUser);
    }

    public static List<String> parseRoles(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of("USER");
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
