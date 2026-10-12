package com.easyshopping.user.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.event.DomainEventNames;
import com.easyshopping.common.event.DomainEventPublisher;
import com.easyshopping.user.domain.UserAccount;
import com.easyshopping.user.dto.InternalDtos.UserCreateRequest;
import com.easyshopping.user.dto.InternalDtos.UserResponse;
import com.easyshopping.user.dto.InternalDtos.UserUpdateRequest;
import com.easyshopping.user.dto.UserDtos.ProfileResponse;
import com.easyshopping.user.dto.UserDtos.ProfileUpdateRequest;
import com.easyshopping.user.repo.UserAccountRepository;
import com.easyshopping.user.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 用户资料 + 内部账号管理（admin-bff 系统管理经 HTTP 调用，禁止直连本服务 DB —— plans/admin-backend.md 1.1.2）。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai"); // 服务端统一时区（DEV.md 4.3）

    private final UserAccountRepository userRepo;
    private final DomainEventPublisher eventPublisher;

    public UserService(UserAccountRepository userRepo, DomainEventPublisher eventPublisher) {
        this.userRepo = userRepo;
        this.eventPublisher = eventPublisher;
    }

    /* ------------------------------------------------------------ 买家资料 */

    /** 买家资料：手机号/邮箱一律脱敏出参（138****8000 / z***@example.com）。 */
    public ProfileResponse getProfile(Long userId) {
        UserAccount user = requireActive(userId);
        return new ProfileResponse(user.getId(), user.getUsername(), user.getNickname(),
                user.getAvatar(), MaskUtil.maskMobile(user.getMobile()), MaskUtil.maskEmail(user.getEmail()),
                user.getGender(), user.getBirthday(),
                user.getCreatedAt() == null ? null
                        : user.getCreatedAt().atZone(ZONE).toLocalDate().format(DAY),
                1, "普通会员", 0, false);
    }

    /** PUT 资料：白名单字段（昵称/头像/邮箱/性别/生日）；手机号换绑 P1（验证码+风控）。 */
    @Transactional
    public boolean updateProfile(Long userId, ProfileUpdateRequest request) {
        UserAccount user = requireActive(userId);
        if (request.nickname() != null) {
            user.setNickname(request.nickname());
        }
        if (request.avatar() != null) {
            user.setAvatar(request.avatar());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.gender() != null) {
            user.setGender(request.gender());
        }
        if (request.birthday() != null) {
            user.setBirthday(request.birthday());
        }
        userRepo.save(user);
        return true;
    }

    /* ------------------------------------------------------------ 内部账号管理（admin-bff 代理） */

    public PageResult<UserResponse> internalList(String role, String keyword, int pageNum, int pageSize) {
        Page<UserAccount> page = userRepo.search(
                role == null || role.isBlank() ? null : role.trim().toUpperCase(),
                blankToNull(keyword),
                PageRequest.of(Math.max(pageNum - 1, 0), Math.min(Math.max(pageSize, 1), 200)));
        List<UserResponse> list = page.getContent().stream().map(this::toInternal).toList();
        return PageResult.of(list, page.getTotalElements(), pageNum, pageSize);
    }

    public UserResponse internalGet(Long id) {
        UserAccount user = userRepo.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(BizErrorCode.USER_NOT_FOUND));
        return toInternal(user);
    }

    @Transactional
    public Long internalCreate(UserCreateRequest request) {
        if (userRepo.existsByUsername(request.username())) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "用户名已存在");
        }
        UserAccount user = new UserAccount(request.username(), blankToNull(request.mobile()),
                PasswordHasher.hash(request.password()),
                request.nickname() == null || request.nickname().isBlank() ? request.username() : request.nickname(),
                request.roles());
        user = userRepo.save(user);
        log.info("[user] internal create userId={} roles={}", user.getId(), user.getRoles());
        return user.getId();
    }

    @Transactional
    public boolean internalUpdate(Long id, UserUpdateRequest request) {
        UserAccount user = userRepo.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(BizErrorCode.USER_NOT_FOUND));
        if (request.nickname() != null) {
            user.setNickname(request.nickname());
        }
        if (request.mobile() != null) {
            user.setMobile(request.mobile());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.password() != null) {
            user.setPasswordHash(PasswordHasher.hash(request.password()));
        }
        if (request.gender() != null) {
            user.setGender(request.gender());
        }
        if (request.birthday() != null) {
            user.setBirthday(request.birthday());
        }
        if (request.status() != null) {
            user.setStatus(request.status());
        }
        userRepo.save(user);
        return true;
    }

    /** 软删 + PIPL 注销事件（TODO(P1) 消费者：合规删除工单/风控同步，architecture/04-messaging §7.1 user.deleted）。 */
    @Transactional
    public boolean internalDelete(Long id) {
        UserAccount user = userRepo.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(BizErrorCode.USER_NOT_FOUND));
        user.softDelete();
        userRepo.save(user);
        eventPublisher.publish(DomainEventNames.USER_DELETED, java.util.Map.of(
                "userId", user.getId(),
                "mobile", user.getMobile() == null ? "" : user.getMobile(),
                "deletedAt", java.time.Instant.now().toString()));
        log.info("[user] internal delete (soft) userId={}", user.getId());
        return true;
    }

    @Transactional
    public boolean internalUpdateRoles(Long id, String rolesCsv) {
        UserAccount user = userRepo.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(BizErrorCode.USER_NOT_FOUND));
        user.setRoles(rolesCsv);
        userRepo.save(user);
        return true;
    }

    /** 解锁/清零失败计数（运营在连续失败后人工解锁）。 */
    @Transactional
    public boolean internalResetLoginFail(Long id) {
        UserAccount user = userRepo.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BizException(BizErrorCode.USER_NOT_FOUND));
        user.resetLoginFail();
        userRepo.save(user);
        return true;
    }

    /* ------------------------------------------------------------ 内部 */

    private UserAccount requireActive(Long userId) {
        if (userId == null) {
            throw new BizException(BizErrorCode.NOT_LOGIN);
        }
        return userRepo.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> new BizException(BizErrorCode.USER_NOT_FOUND));
    }

    private UserResponse toInternal(UserAccount u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getNickname(), u.getMobile(),
                u.getEmail(), u.getGender(), u.getBirthday(), u.getAvatar(), u.getStatus(),
                AuthService.parseRoles(u.getRoles()), u.getLoginFailCount(),
                u.getLastLoginAt(), u.getCreatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
