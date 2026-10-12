package com.admin.web;

import com.admin.security.OperationLog;
import com.admin.security.RequirePerm;
import com.admin.system.domain.AdminConfig;
import com.admin.system.dto.SystemDtos.ConfigReq;
import com.admin.system.dto.SystemDtos.ConfigVO;
import com.admin.system.repo.AdminConfigRepository;
import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.idempotent.Idempotent;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
 * 系统管理-系统参数（plans/admin-backend.md 2.5.2；web-admin 契约：key/value/remark）。
 * TODO(P1)：变更历史表 admin_config_history + 一键回滚。
 */
@RestController
public class SystemConfigController {

    private final AdminConfigRepository configRepo;

    public SystemConfigController(AdminConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @GetMapping("/api/admin/system/config")
    @RequirePerm("system:config")
    public ApiResponse<PageResult<ConfigVO>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<AdminConfig> page = configRepo.findAll(
                PageRequest.of(Math.max(pageNum - 1, 0), pageSize, Sort.by(Sort.Direction.ASC, "id")));
        return ApiResponse.ok(PageResult.of(
                page.getContent().stream()
                        .map(c -> new ConfigVO(c.getId(), c.getConfigKey(), c.getConfigValue(), c.getRemark()))
                        .toList(),
                page.getTotalElements(), pageNum, pageSize));
    }

    /** 新增/更新（key 唯一，存在即更新 —— upsert）。 */
    @PostMapping("/api/admin/system/config")
    @RequirePerm("system:config")
    @Idempotent
    @OperationLog(module = "system", action = "保存系统参数")
    public ApiResponse<Map<String, Object>> save(@Valid @RequestBody ConfigReq request) {
        AdminConfig existing = configRepo.findByConfigKey(request.key());
        if (existing != null) {
            existing.setConfigValue(request.value());
            existing.setRemark(request.remark());
            configRepo.save(existing);
            return ApiResponse.ok(Map.of("id", existing.getId(), "success", true));
        }
        AdminConfig saved = configRepo.save(new AdminConfig(request.key(), request.value(), request.remark()));
        return ApiResponse.ok(Map.of("id", saved.getId(), "success", true));
    }

    @PutMapping("/api/admin/system/config/{id}")
    @RequirePerm("system:config")
    @OperationLog(module = "system", action = "编辑系统参数")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @Valid @RequestBody ConfigReq request) {
        AdminConfig config = configRepo.findById(id)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "配置不存在: " + id));
        if (!config.getConfigKey().equals(request.key())
                && configRepo.findByConfigKey(request.key()) != null) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "配置 key 已存在: " + request.key());
        }
        config.setConfigKey(request.key());
        config.setConfigValue(request.value());
        config.setRemark(request.remark());
        configRepo.save(config);
        return ApiResponse.ok(Map.of("success", true));
    }

    @DeleteMapping("/api/admin/system/config/{id}")
    @RequirePerm("system:config")
    @OperationLog(module = "system", action = "删除系统参数")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        AdminConfig config = configRepo.findById(id)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "配置不存在: " + id));
        configRepo.delete(config);
        return ApiResponse.ok(Map.of("success", true));
    }
}
