package com.easyshopping.user.service;

import com.easyshopping.user.domain.UserAccount;
import com.easyshopping.user.repo.UserAccountRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录审计落库（独立事务）。
 *
 * <p>失败计数/锁定必须在抛出业务异常<b>之前</b>提交，否则随异常回滚丢失
 * （登录失败 → 抛 2002 → 事务回滚 → 计数清零，锁定永远不会触发）。故用 REQUIRES_NEW。</p>
 */
@Component
public class LoginAuditService {

    private final UserAccountRepository userRepo;

    public LoginAuditService(UserAccountRepository userRepo) {
        this.userRepo = userRepo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginFailure(Long userId, int maxFails, int lockMinutes) {
        UserAccount user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            return;
        }
        user.recordLoginFailure(maxFails, lockMinutes);
        userRepo.saveAndFlush(user);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLoginSuccess(Long userId) {
        UserAccount user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            return;
        }
        user.recordLoginSuccess();
        userRepo.saveAndFlush(user);
    }
}
