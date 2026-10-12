package com.admin.system.repo;

import com.admin.system.domain.AdminUserExt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserExtRepository extends JpaRepository<AdminUserExt, Long> {

    /** 部门下用户数（部门删除守卫 + 树展示）。 */
    long countByDeptId(Long deptId);
}
