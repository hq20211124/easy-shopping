package com.search.repo;

import com.search.domain.SearchProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 搜索冗余表仓储（Specification 动态拼条件 + cursor 谓词）。 */
public interface SearchProductRepository extends JpaRepository<SearchProduct, Long>,
        JpaSpecificationExecutor<SearchProduct> {

    Optional<SearchProduct> findBySpuId(Long spuId);

    List<SearchProduct> findBySpuIdIn(Collection<Long> spuIds);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional // 派生删除需要事务（无业务逻辑，直接挂在仓储）
    void deleteBySpuIdIn(Collection<Long> spuIds);
}
