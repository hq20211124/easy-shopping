package com.admin.system.repo;

import com.admin.system.domain.AdminRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminRoleRepository extends JpaRepository<AdminRole, Long> {

    AdminRole findByCode(String code);

    List<AdminRole> findByStatus(Integer status);
}
