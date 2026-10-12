package com.admin.system.repo;

import com.admin.system.domain.AdminDept;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminDeptRepository extends JpaRepository<AdminDept, Long> {

    long countByParentId(Long parentId);

    boolean existsByName(String name);
}
