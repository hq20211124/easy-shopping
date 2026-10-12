package com.admin.web;

import com.admin.security.OperationLog;
import com.admin.security.RequirePerm;
import com.admin.system.dto.SystemDtos.RolePermissionsReq;
import com.admin.system.dto.SystemDtos.RoleReq;
import com.admin.system.dto.SystemDtos.RoleVO;
import com.admin.system.service.SystemRoleService;
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

import java.util.List;
import java.util.Map;

/** 系统管理-角色（plans/admin-backend.md 2.2.2/2.5.2；web-admin src/api/user.ts 契约）。 */
@RestController
public class SystemRoleController {

    private final SystemRoleService roleService;

    public SystemRoleController(SystemRoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping("/api/admin/system/role")
    @RequirePerm("system:role")
    public ApiResponse<PageResult<RoleVO>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(roleService.list(pageNum, pageSize));
    }

    @PostMapping("/api/admin/system/role")
    @RequirePerm("system:role")
    @Idempotent
    @OperationLog(module = "system", action = "创建角色")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody RoleReq request) {
        Long roleId = roleService.create(request);
        return ApiResponse.ok(Map.of("roleId", roleId, "success", true));
    }

    @PutMapping("/api/admin/system/role/{id}")
    @RequirePerm("system:role")
    @OperationLog(module = "system", action = "编辑角色")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @Valid @RequestBody RoleReq request) {
        return ApiResponse.ok(Map.of("success", roleService.update(id, request)));
    }

    @DeleteMapping("/api/admin/system/role/{id}")
    @RequirePerm("system:role")
    @OperationLog(module = "system", action = "删除角色")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("success", roleService.delete(id)));
    }

    /** 查询角色已授权限（web-admin getRolePermissions）。 */
    @GetMapping("/api/admin/system/role/{id}/permissions")
    @RequirePerm("system:role")
    public ApiResponse<Map<String, List<String>>> permissions(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("permissions", roleService.permissions(id)));
    }

    /** 角色授权（写入后刷新相关用户 permVer，plans 2.1.4）。 */
    @PutMapping("/api/admin/system/role/{id}/permissions")
    @RequirePerm("system:role")
    @OperationLog(module = "system", action = "角色授权")
    public ApiResponse<Map<String, Object>> setPermissions(@PathVariable Long id,
                                                           @Valid @RequestBody RolePermissionsReq request) {
        return ApiResponse.ok(Map.of("success", roleService.setPermissions(id, request)));
    }
}
