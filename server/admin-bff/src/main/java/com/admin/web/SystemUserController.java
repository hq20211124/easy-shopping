package com.admin.web;

import com.admin.security.OperationLog;
import com.admin.security.RequirePerm;
import com.admin.system.dto.SystemDtos.AdminUserCreateReq;
import com.admin.system.dto.SystemDtos.AdminUserUpdateReq;
import com.admin.system.dto.SystemDtos.AdminUserVO;
import com.admin.system.dto.SystemDtos.UserRolesReq;
import com.admin.system.service.SystemUserService;
import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.idempotent.Idempotent;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 系统管理-用户（plans/admin-backend.md 2.5.2；web-admin src/api/user.ts 契约）。 */
@RestController
public class SystemUserController {

    private final SystemUserService systemUserService;

    public SystemUserController(SystemUserService systemUserService) {
        this.systemUserService = systemUserService;
    }

    @GetMapping("/api/admin/system/user")
    @RequirePerm("system:user")
    public ApiResponse<PageResult<AdminUserVO>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(systemUserService.list(keyword, pageNum, pageSize));
    }

    @PostMapping("/api/admin/system/user")
    @RequirePerm("system:user")
    @Idempotent
    @OperationLog(module = "system", action = "创建管理员")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody AdminUserCreateReq request) {
        Long userId = systemUserService.create(request);
        return ApiResponse.ok(Map.of("userId", userId, "success", true));
    }

    @PutMapping("/api/admin/system/user/{id}")
    @RequirePerm("system:user")
    @OperationLog(module = "system", action = "编辑管理员")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @Valid @RequestBody AdminUserUpdateReq request) {
        return ApiResponse.ok(Map.of("success", systemUserService.update(id, request)));
    }

    @DeleteMapping("/api/admin/system/user/{id}")
    @RequirePerm("system:user")
    @OperationLog(module = "system", action = "删除管理员")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("success", systemUserService.delete(id)));
    }

    @PutMapping("/api/admin/system/user/{id}/roles")
    @RequirePerm("system:role")
    @OperationLog(module = "system", action = "分配用户角色")
    public ApiResponse<Map<String, Object>> setRoles(@PathVariable Long id,
                                                     @Valid @RequestBody UserRolesReq request) {
        return ApiResponse.ok(Map.of("success", systemUserService.setRoles(id, request)));
    }
}
