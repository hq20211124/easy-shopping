package com.admin.system.repo;

import com.admin.system.domain.AdminPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminPermissionRepository extends JpaRepository<AdminPermission, Long> {

    List<AdminPermission> findByParentId(Long parentId);

    List<AdminPermission> findByType(String type);

    long countByParentId(Long parentId);
}
