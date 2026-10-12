package com.product.repo;

import com.product.domain.Sku;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** SKU 仓储。 */
public interface SkuRepository extends JpaRepository<Sku, Long> {

    List<Sku> findBySpuIdAndDeletedFalseOrderBySkuCodeAsc(Long spuId);

    List<Sku> findByIdInAndDeletedFalse(Collection<Long> ids);

    long countBySpuIdInAndDeletedFalse(Collection<Long> spuIds);

    void deleteBySpuId(Long spuId);
}
