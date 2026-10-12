package com.admin.system.service;

import com.admin.client.ClientDtos.InternalUserDto;
import com.admin.client.ClientDtos.InternalUserWrite;
import com.admin.client.CoreUserClient;
import com.admin.rbac.RbacService;
import com.admin.system.domain.AdminRole;
import com.admin.system.domain.AdminUserRole;
import com.admin.system.dto.SystemDtos.AdminUserCreateReq;
import com.admin.system.dto.SystemDtos.AdminUserUpdateReq;
import com.admin.system.dto.SystemDtos.AdminUserVO;
import com.admin.system.dto.SystemDtos.UserRolesReq;
import com.admin.system.repo.AdminRoleRepository;
import com.admin.system.repo.AdminUserRoleRepository;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 管理端用户管理：账号本体经 HTTP 由 core-user 管理（禁止直查其 DB），
 * RBAC 角色绑定/部门/permVer 存本地。
 */
@Service
public class SystemUserService {

    private static final Logger log = LoggerFactory.getLogger(SystemUserService.class);

    private final CoreUserClient coreUserClient;
    private final AdminUserRoleRepository userRoleRepo;
    private final AdminRoleRepository roleRepo;
    private final RbacService rbacService;

    public SystemUserService(CoreUserClient coreUserClient,
                             AdminUserRoleRepository userRoleRepo,
                             AdminRoleRepository roleRepo,
                             RbacService rbacService) {
        this.coreUserClient = coreUserClient;
        this.userRoleRepo = userRoleRepo;
        this.roleRepo = roleRepo;
        this.rbacService = rbacService;
    }

    public PageResult<AdminUserVO> list(String keyword, int pageNum, int pageSize) {
        PageResult<InternalUserDto> page = coreUserClient.listUsers("ADMIN", keyword, pageNum, pageSize);
        List<AdminUserVO> list = page.list().stream().map(this::toVO).toList();
        return PageResult.of(list, page.total(), pageNum, pageSize);
    }

    @Transactional
    public Long create(AdminUserCreateReq request) {
        validateRoles(request.roles());
        Long userId = coreUserClient.createUser(InternalUserWrite.create(
                request.username(), request.password(), request.nickname(), null));
        rbacService.ensureUserExt(userId, request.deptId());
        bindRoles(userId, request.roles());
        rbacService.bumpPermVerForUser(userId);
        log.info("[system-user] created userId={} roles={}", userId, request.roles());
        return userId;
    }

    @Transactional
    public boolean update(Long userId, AdminUserUpdateReq request) {
        coreUserClient.updateUser(userId, new InternalUserWrite(null, request.password(), request.nickname(),
                request.mobile(), null, request.status(), null, null, null));
        return true;
    }

    /** 账号软删（core-user 侧发 user.deleted 事件）；本地角色绑定保留供审计，TODO(P1) 清理任务。 */
    @Transactional
    public boolean delete(Long userId) {
        boolean ok = coreUserClient.deleteUser(userId);
        log.info("[system-user] deleted userId={}", userId);
        return ok;
    }

    @Transactional
    public boolean setRoles(Long userId, UserRolesReq request) {
        validateRoles(request.roles());
        // core-user 侧角色仍是 ADMIN（管理端入口标志）；细分角色存本地
        coreUserClient.updateUserRoles(userId, "ADMIN");
        bindRoles(userId, request.roles());
        rbacService.bumpPermVerForUser(userId);
        return true;
    }

    private void bindRoles(Long userId, List<String> roleCodes) {
        if (roleCodes == null) {
            return;
        }
        userRoleRepo.deleteByUserId(userId);
        for (String code : roleCodes) {
            AdminRole role = roleRepo.findByCode(code);
            if (role == null) {
                throw new BizException(BizErrorCode.PARAM_ERROR, "角色不存在: " + code);
            }
            userRoleRepo.save(new AdminUserRole(userId, role.getId()));
        }
    }

    private void validateRoles(List<String> roleCodes) {
        if (roleCodes == null) {
            return;
        }
        for (String code : roleCodes) {
            if (roleRepo.findByCode(code) == null) {
                throw new BizException(BizErrorCode.PARAM_ERROR, "角色不存在: " + code);
            }
        }
    }

    private AdminUserVO toVO(InternalUserDto dto) {
        List<String> roleCodes = userRoleRepo.findByUserId(dto.userId()).stream()
                .map(ur -> roleRepo.findById(ur.getRoleId()).map(AdminRole::getCode).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
        Long deptId = rbacService.deptId(dto.userId());
        return new AdminUserVO(dto.userId(), dto.username(), dto.nickname(),
                rbacService.deptName(deptId), roleCodes, dto.status(),
                dto.lastLoginAt(), dto.createdAt());
    }
}
