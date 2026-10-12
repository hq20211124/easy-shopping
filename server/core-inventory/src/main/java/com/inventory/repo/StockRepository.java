package com.inventory.repo;

import com.inventory.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {

    Optional<Stock> findBySkuId(Long skuId);

    List<Stock> findBySkuIdIn(Collection<Long> skuIds);
}
