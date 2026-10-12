package com.product.repo;

import com.product.domain.Sku;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** SKU 仓储。 */
public interface SkuRepository extends JpaRepository<Sku, Long> {

    List<Sku> findBySpuIdAndDeletedFalseOrderBySkuCodeAsc(Long spuId);

    /**
     * 查该 SPU 的<b>全部</b> SKU（含已软删行）。
     *
     * <p>编辑合并用：既要按 skuId 精确定位既有行，也要能识别「库中已软删、请求又带回来」的过期提交。</p>
     */
    List<Sku> findBySpuId(Long spuId);

    List<Sku> findByIdInAndDeletedFalse(Collection<Long> ids);

    long countBySpuIdInAndDeletedFalse(Collection<Long> spuIds);

    void deleteBySpuId(Long spuId);
}
