package com.promotion.repo;

import com.promotion.domain.CouponTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/** 券模板仓储：超领/核销计数一律条件更新（原子 CAS），并发下不会超发。 */
public interface CouponTemplateRepository extends JpaRepository<CouponTemplate, Long> {

    /**
     * 领取预占（防超领核心）：仅当 生效 + 未超发放总量 + 时间窗内 才自增已领数。
     * 返回 0 表示已领完/未开始/已结束/已下线（调用方按预检结果细化错误码）。
     */
    @Modifying
    @Query("""
            UPDATE CouponTemplate t
            SET t.receivedCount = t.receivedCount + 1, t.updatedAt = :now
            WHERE t.id = :id AND t.status = 1
              AND t.receivedCount < t.totalCount
              AND :now >= t.startTime AND :now <= t.endTime
            """)
    int reserveStock(@Param("id") Long id, @Param("now") Instant now);

    /**
     * 核销 +1。
     *
     * <p>{@code clearAutomatically}：批量更新绕过一级缓存，不清理时随后重读模板会拿到旧 usedCount
     * 并原样返回给调用方（核销响应里 usedCount 假回退）。</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CouponTemplate t SET t.usedCount = t.usedCount + 1, t.updatedAt = :now WHERE t.id = :id")
    int increaseUsedCount(@Param("id") Long id, @Param("now") Instant now);

    /** 关单退回 -1（usedCount>0 保护，防负数）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CouponTemplate t SET t.usedCount = t.usedCount - 1, t.updatedAt = :now WHERE t.id = :id AND t.usedCount > 0")
    int decreaseUsedCount(@Param("id") Long id, @Param("now") Instant now);
}
