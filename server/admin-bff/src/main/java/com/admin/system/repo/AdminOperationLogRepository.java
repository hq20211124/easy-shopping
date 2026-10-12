package com.admin.system.repo;

import com.admin.system.domain.AdminOperationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface AdminOperationLogRepository extends JpaRepository<AdminOperationLog, Long> {

    /** P0 简单 OR 过滤；TODO(P1) JPA Specification / MyBatis-Plus 多条件组合 */
    Page<AdminOperationLog> findByOperatorNameContainingIgnoreCaseOrModuleContainingIgnoreCase(
            String operator, String module, Pageable pageable);

    Page<AdminOperationLog> findByCreatedAtBetween(Instant start, Instant end, Pageable pageable);
}
