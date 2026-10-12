package com.product.repo;

import com.product.domain.Spu;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** SPU 仓储。 */
public interface SpuRepository extends JpaRepository<Spu, Long>, JpaSpecificationExecutor<Spu> {

    /** 买家端可见：已上架 + 未进回收站 */
    Page<Spu> findByDeletedFalseAndShelfStatus(Integer shelfStatus, Pageable pageable);

    boolean existsByCategoryIdAndDeletedFalse(Long categoryId);

    long countByIdInAndDeletedFalse(java.util.Collection<Long> ids);
}
