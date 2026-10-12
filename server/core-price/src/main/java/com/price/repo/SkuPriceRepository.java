package com.price.repo;

import com.price.domain.SkuPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** SKU 价格仓储。 */
public interface SkuPriceRepository extends JpaRepository<SkuPrice, Long> {

    Optional<SkuPrice> findBySkuId(Long skuId);

    List<SkuPrice> findBySkuIdIn(Collection<Long> skuIds);
}
