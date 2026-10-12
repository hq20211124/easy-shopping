package com.admin.system.repo;

import com.admin.system.domain.AdminUserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface AdminUserRoleRepository extends JpaRepository<AdminUserRole, Long> {

    List<AdminUserRole> findByUserId(Long userId);

    List<AdminUserRole> findByRoleId(Long roleId);

    long countByRoleId(Long roleId);

    @Transactional
    void deleteByUserId(Long userId);

    @Transactional
    void deleteByRoleId(Long roleId);
}
