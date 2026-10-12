package com.admin.system.repo;

import com.admin.system.domain.AdminConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminConfigRepository extends JpaRepository<AdminConfig, Long> {

    AdminConfig findByConfigKey(String configKey);
}
