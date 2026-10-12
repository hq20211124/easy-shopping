package com.admin.web;

import com.admin.rbac.RbacService;
import com.admin.security.OperationLog;
import com.admin.security.RequirePerm;
import com.admin.system.domain.AdminPermission;
import com.admin.system.dto.SystemDtos.PermissionReq;
import com.admin.system.repo.AdminPermissionRepository;
import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.idempotent.Idempotent;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 系统管理-权限点（plans/admin-backend.md 2.2.3 注册表 / 2.5.2）。
 *
 * <p>P0 提供树查询 + 增删；权限码命名「模块:资源」，商品域统一 product:*（禁 goods:*）。</p>
 */
@RestController
public class SystemPermissionController {

    private final RbacService rbacService;
    private final AdminPermissionRepository permissionRepo;

    public SystemPermissionController(RbacService rbacService, AdminPermissionRepository permissionRepo) {
        this.rbacService = rbacService;
        this.permissionRepo = permissionRepo;
    }

    /** 权限点树（菜单+按钮+接口，web-admin getPermissionTree）。 */
    @GetMapping("/api/admin/system/permission/tree")
    @RequirePerm("system:permission")
    public ApiResponse<List<RbacService.PermTreeNode>> tree() {
        return ApiResponse.ok(rbacService.permissionTree());
    }

    @PostMapping("/api/admin/system/permission")
    @RequirePerm("system:permission")
    @Idempotent
    @OperationLog(module = "system", action = "创建权限点")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody PermissionReq request) {
        if (permissionRepo.findAll().stream().anyMatch(p -> p.getCode().equals(request.code()))) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "权限码已存在: " + request.code());
        }
        if (!"menu".equals(request.type()) && !"permission".equals(request.type())) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "type 必须为 menu 或 permission");
        }
        if (request.parentId() != null && permissionRepo.findById(request.parentId()).isEmpty()) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "父节点不存在: " + request.parentId());
        }
        AdminPermission saved = permissionRepo.save(new AdminPermission(
                request.code(), request.title(), request.type(), request.parentId(),
                request.path(), request.requiredPerm(), request.orderNum()));
        return ApiResponse.ok(Map.of("id", saved.getId(), "success", true));
    }

    @DeleteMapping("/api/admin/system/permission/{id}")
    @RequirePerm("system:permission")
    @Transactional
    @OperationLog(module = "system", action = "删除权限点")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        AdminPermission perm = permissionRepo.findById(id)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "权限点不存在: " + id));
        if (permissionRepo.countByParentId(id) > 0) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "存在子节点，无法删除");
        }
        permissionRepo.delete(perm);
        return ApiResponse.ok(Map.of("success", true));
    }
}
