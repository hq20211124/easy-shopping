package com.admin.web;

import com.admin.security.RequirePerm;
import com.admin.system.domain.AdminOperationLog;
import com.admin.system.repo.AdminOperationLogRepository;
import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.PageResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 系统管理-操作日志（plans/admin-backend.md 2.4 / 2.5.2；web-admin src/api/system.ts 契约）。
 *
 * <p>记录由 @OperationLog AOP 落库；此处仅查询。
 * P0 支持 operator/module 关键字（OR 语义）+ 时间范围；TODO(P1) 多条件组合查询 + 前后值对比详情页。</p>
 */
@RestController
public class SystemLogController {

    private final AdminOperationLogRepository logRepo;

    public SystemLogController(AdminOperationLogRepository logRepo) {
        this.logRepo = logRepo;
    }

    @GetMapping("/api/admin/system/operation-log")
    @RequirePerm("system:log")
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String module,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAtStart,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAtEnd,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        PageRequest pageReq = PageRequest.of(Math.max(pageNum - 1, 0), pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AdminOperationLog> page;
        boolean hasKeyword = (operator != null && !operator.isBlank()) || (module != null && !module.isBlank());
        // TODO(P1) JPA Specification 组合查询；P0 关键字与时间范围二选一（关键字优先）
        if (hasKeyword) {
            page = logRepo.findByOperatorNameContainingIgnoreCaseOrModuleContainingIgnoreCase(
                    operator == null ? "" : operator, module == null ? "" : module, pageReq);
        } else if (createdAtStart != null || createdAtEnd != null) {
            Instant start = createdAtStart == null ? Instant.EPOCH : createdAtStart.truncatedTo(ChronoUnit.SECONDS);
            Instant end = createdAtEnd == null ? Instant.now().plusSeconds(86400) : createdAtEnd.truncatedTo(ChronoUnit.SECONDS);
            page = logRepo.findByCreatedAtBetween(start, end, pageReq);
        } else {
            page = logRepo.findAll(pageReq);
        }
        return ApiResponse.ok(PageResult.of(page.getContent().stream().map(this::toVO).toList(),
                page.getTotalElements(), pageNum, pageSize));
    }

    /** 对齐 web-admin OperationLog 结构（beforeData P0 不落库恒 null，afterData 为敏感字段剥离后的入/出参 JSON）。 */
    private Map<String, Object> toVO(AdminOperationLog l) {
        Map<String, Object> vo = new java.util.LinkedHashMap<>();
        vo.put("id", l.getId());
        vo.put("operator", l.getOperatorName());
        vo.put("module", l.getModule());
        vo.put("action", l.getAction());
        vo.put("content", l.getContent());
        vo.put("ip", l.getIp());
        vo.put("status", l.getStatus());
        vo.put("duration", l.getDurationMs());
        vo.put("createdAt", l.getCreatedAt());
        vo.put("beforeData", null);
        vo.put("afterData", l.getAfterData());
        return vo;
    }
}
