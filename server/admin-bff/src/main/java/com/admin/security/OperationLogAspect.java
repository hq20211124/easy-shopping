package com.admin.security;

import com.admin.system.domain.AdminOperationLog;
import com.admin.system.repo.AdminOperationLogRepository;
import com.easyshopping.common.api.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 操作日志切面（plans/admin-backend.md 2.4.2）。
 *
 * <p>P0 同步落库（记录耗时/结果/操作人/入参摘要）；TODO(P1) 异步批量 + 归档。
 * 敏感字段（password 等）在序列化前已按字段名黑名单剔除。</p>
 */
@Aspect
@Component
public class OperationLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationLogAspect.class);
    private static final java.util.Set<String> SENSITIVE_KEYS = java.util.Set.of(
            "password", "newPassword", "oldPassword", "captchaCode", "accessToken", "refreshToken");

    private final AdminOperationLogRepository operationLogRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OperationLogAspect(AdminOperationLogRepository operationLogRepo) {
        this.operationLogRepo = operationLogRepo;
    }

    @Around("@annotation(op)")
    public Object around(ProceedingJoinPoint pjp, OperationLog op) throws Throwable {
        long start = System.currentTimeMillis();
        AdminOperationLog record = AdminOperationLog.create();
        record.setModule(op.module());
        record.setAction(op.action());
        record.setOperatorId(UserContext.userId());
        record.setContent(truncate(describeArgs(pjp.getArgs()), 2000));
        fillRequest(record);
        Object result;
        try {
            result = pjp.proceed();
            record.setStatus(1);
            record.setAfterData(truncate(safeToJson(result), 2000));
            return result;
        } catch (Throwable e) {
            record.setStatus(0);
            record.setErrorMsg(truncate(e.getMessage(), 512));
            throw e;
        } finally {
            record.setDurationMs((int) (System.currentTimeMillis() - start));
            try {
                operationLogRepo.save(record);
            } catch (Exception e) {
                // 日志失败不能影响业务
                log.error("[op-log] save failed module={} action={}", op.module(), op.action(), e);
            }
        }
    }

    private void fillRequest(AdminOperationLog record) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            record.setMethod(request.getMethod());
            record.setPath(request.getRequestURI());
            String ip = request.getHeader("X-Forwarded-For");
            record.setIp(ip == null || ip.isBlank() ? request.getRemoteAddr() : ip.split(",")[0].trim());
        }
    }

    private String describeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        return safeToJson(args);
    }

    private String safeToJson(Object value) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(value);
            if (node != null && node.isObject()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                        .remove(SENSITIVE_KEYS.stream().toList());
            } else if (node != null && node.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode element : node) {
                    if (element.isObject()) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) element)
                                .remove(SENSITIVE_KEYS.stream().toList());
                    }
                }
            }
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
