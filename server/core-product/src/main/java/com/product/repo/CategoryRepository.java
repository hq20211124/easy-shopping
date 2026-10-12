package com.product.repo;

import com.product.domain.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/** 类目仓储（P0 H2；P1 迁 MySQL 由 Flyway 管 DDL，见 DEV.md 4.3）。 */
public interface CategoryRepository extends JpaRepository<Category, Long>, JpaSpecificationExecutor<Category> {

    List<Category> findByDeletedFalseOrderBySortAscIdAsc();

    Page<Category> findByDeletedFalse(org.springframework.data.domain.Pageable pageable);

    boolean existsByParentIdAndDeletedFalse(Long parentId);

    List<Category> findByIdInAndDeletedFalse(java.util.Collection<Long> ids);
}
