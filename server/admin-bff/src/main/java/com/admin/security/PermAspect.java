package com.admin.security;

import com.admin.rbac.RbacService;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.UserContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * @RequirePerm 切面：查本地 RBAC 权限点集合（P0 每请求查库；P1 Redis + permVer 失效）。
 * 业务侧兜底校验（列表接口按数据范围过滤）由各服务自行负责 —— plans 2.3.2「双重校验」。
 */
@Aspect
@Component
public class PermAspect {

    private static final Logger log = LoggerFactory.getLogger(PermAspect.class);

    private final RbacService rbacService;

    public PermAspect(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    @Around("@annotation(requirePerm)")
    public Object check(ProceedingJoinPoint pjp, RequirePerm requirePerm) throws Throwable {
        long userId = UserContext.requireUserId();
        if (!rbacService.hasPerm(userId, requirePerm.value())) {
            log.warn("[perm] deny userId={} need={}", userId, requirePerm.value());
            throw new BizException(BizErrorCode.NO_PERMISSION, "缺少权限：" + requirePerm.value());
        }
        return pjp.proceed();
    }
}
