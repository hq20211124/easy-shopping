package com.admin.system.service;

import com.admin.rbac.RbacService;
import com.admin.system.domain.AdminPermission;
import com.admin.system.domain.AdminRole;
import com.admin.system.domain.AdminRolePermission;
import com.admin.system.dto.SystemDtos.RolePermissionsReq;
import com.admin.system.dto.SystemDtos.RoleReq;
import com.admin.system.dto.SystemDtos.RoleVO;
import com.admin.system.repo.AdminPermissionRepository;
import com.admin.system.repo.AdminRolePermissionRepository;
import com.admin.system.repo.AdminRoleRepository;
import com.admin.system.repo.AdminUserRoleRepository;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 角色管理（plans/admin-backend.md 2.2.2 / 2.5.2）。
 *
 * <p>防提权约束：内置角色（super_admin）不可编辑/删除/改授权；
 * 删除前校验绑定用户；授权变更后刷新相关用户 permVer（2.1.4）。</p>
 */
@Service
public class SystemRoleService {

    private static final Logger log = LoggerFactory.getLogger(SystemRoleService.class);

    private final AdminRoleRepository roleRepo;
    private final AdminRolePermissionRepository rolePermissionRepo;
    private final AdminPermissionRepository permissionRepo;
    private final AdminUserRoleRepository userRoleRepo;
    private final RbacService rbacService;

    public SystemRoleService(AdminRoleRepository roleRepo,
                             AdminRolePermissionRepository rolePermissionRepo,
                             AdminPermissionRepository permissionRepo,
                             AdminUserRoleRepository userRoleRepo,
                             RbacService rbacService) {
        this.roleRepo = roleRepo;
        this.rolePermissionRepo = rolePermissionRepo;
        this.permissionRepo = permissionRepo;
        this.userRoleRepo = userRoleRepo;
        this.rbacService = rbacService;
    }

    public PageResult<RoleVO> list(int pageNum, int pageSize) {
        Page<AdminRole> page = roleRepo.findAll(
                PageRequest.of(Math.max(pageNum - 1, 0), pageSize, Sort.by(Sort.Direction.ASC, "id")));
        List<RoleVO> list = page.getContent().stream()
                .map(r -> new RoleVO(r.getId(), r.getCode(), r.getName(), r.getDataScope(), r.getStatus(),
                        userRoleRepo.countByRoleId(r.getId()), r.getRemark(), r.isBuiltin()))
                .toList();
        return PageResult.of(list, page.getTotalElements(), pageNum, pageSize);
    }

    @Transactional
    public Long create(RoleReq request) {
        if (roleRepo.findByCode(request.code()) != null) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "角色编码已存在: " + request.code());
        }
        validateDataScope(request.dataScope());
        AdminRole role = roleRepo.save(new AdminRole(request.code(), request.name(),
                request.dataScope(), false, request.remark()));
        log.info("[system-role] created roleId={} code={}", role.getId(), role.getCode());
        return role.getId();
    }

    @Transactional
    public boolean update(Long roleId, RoleReq request) {
        AdminRole role = requireRole(roleId);
        guardNotBuiltin(role, "编辑");
        if (!role.getCode().equals(request.code()) && roleRepo.findByCode(request.code()) != null) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "角色编码已存在: " + request.code());
        }
        validateDataScope(request.dataScope());
        role.setCode(request.code());
        role.setName(request.name());
        role.setDataScope(request.dataScope());
        role.setStatus(request.status() == null ? role.getStatus() : request.status());
        role.setRemark(request.remark());
        roleRepo.save(role);
        rbacService.bumpPermVerForRole(roleId);
        return true;
    }

    /** 删除角色：内置角色不可删；存在绑定用户时拒绝（plans 2.5.2）。 */
    @Transactional
    public boolean delete(Long roleId) {
        AdminRole role = requireRole(roleId);
        guardNotBuiltin(role, "删除");
        long bound = userRoleRepo.countByRoleId(roleId);
        if (bound > 0) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "该角色下存在 " + bound + " 个用户，无法删除");
        }
        rolePermissionRepo.deleteByRoleId(roleId);
        roleRepo.delete(role);
        log.info("[system-role] deleted roleId={} code={}", roleId, role.getCode());
        return true;
    }

    public List<String> permissions(Long roleId) {
        requireRole(roleId);
        return rolePermissionRepo.findByRoleId(roleId).stream()
                .map(AdminRolePermission::getPermissionCode)
                .toList();
    }

    /** 角色授权：全量替换绑定关系，写入后刷新相关用户 permVer（plans 2.5.2）。 */
    @Transactional
    public boolean setPermissions(Long roleId, RolePermissionsReq request) {
        AdminRole role = requireRole(roleId);
        guardNotBuiltin(role, "授权");
        Set<String> all = Set.copyOf(permissionRepo.findAll().stream().map(AdminPermission::getCode).toList());
        for (String code : request.permissions()) {
            if (!all.contains(code)) {
                throw new BizException(BizErrorCode.PARAM_ERROR, "权限点不存在: " + code);
            }
        }
        rolePermissionRepo.deleteByRoleId(roleId);
        for (String code : request.permissions()) {
            rolePermissionRepo.save(new AdminRolePermission(roleId, code));
        }
        int affected = rbacService.bumpPermVerForRole(roleId);
        log.info("[system-role] permissions updated roleId={} count={} permVerBumped={}",
                roleId, request.permissions().size(), affected);
        return true;
    }

    private AdminRole requireRole(Long roleId) {
        return roleRepo.findById(roleId)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "角色不存在: " + roleId));
    }

    /** 防提权（plans 2.2.2）：内置角色禁止改名/删除/改授权。 */
    private void guardNotBuiltin(AdminRole role, String action) {
        if (role.isBuiltin()) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "内置角色不允许" + action);
        }
    }

    private void validateDataScope(Integer dataScope) {
        if (dataScope == null || dataScope < 1 || dataScope > 6) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "数据范围必须为 1~6");
        }
    }
}
