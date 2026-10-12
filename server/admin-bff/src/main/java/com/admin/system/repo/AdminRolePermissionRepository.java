package com.admin.system.repo;

import com.admin.system.domain.AdminRolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AdminRolePermissionRepository extends JpaRepository<AdminRolePermission, Long> {

    List<AdminRolePermission> findByRoleId(Long roleId);

    List<AdminRolePermission> findByRoleIdIn(List<Long> roleIds);

    @Transactional
    void deleteByRoleId(Long roleId);
}
