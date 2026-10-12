package com.promotion.repo;

import com.promotion.domain.CouponRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** 用户券记录仓储：核销/退回/过期翻转全部条件更新（多实例安全的并发控制）。 */
public interface CouponRecordRepository extends JpaRepository<CouponRecord, Long> {

    /** 每人限领计数（同一模板下该用户已领张数，含已用/已过期）。 */
    long countByCouponIdAndUserId(Long couponId, Long userId);

    /** 某用户在某模板下的全部券记录（走 idx_coupon_user，限领断言/对账用）。 */
    List<CouponRecord> findByCouponIdAndUserId(Long couponId, Long userId);

    List<CouponRecord> findByCouponId(Long couponId);

    List<CouponRecord> findByUserIdOrderByReceivedAtDesc(Long userId);

    List<CouponRecord> findByUserIdAndStatusOrderByReceivedAtDesc(Long userId, Integer status);

    /**
     * 核销（防并发重复核销核心）：仅当 可用 + 未过期 才置 used 并绑定订单。
     * 返回 0 由调用方按当前状态细化（同订单幂等成功 / 他订单占用 / 已过期）。
     *
     * <p>{@code clearAutomatically}：批量 JPQL 更新绕过一级缓存，若不清理，随后
     * {@code findById} 会命中旧实体，把「available」当成核销结果返回给调用方。</p>
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CouponRecord r
            SET r.status = :usedStatus, r.usedOrderId = :orderId, r.usedTime = :now
            WHERE r.id = :id AND r.status = :availableStatus AND r.expireAt >= :now
            """)
    int casUse(@Param("id") Long id,
               @Param("orderId") Long orderId,
               @Param("now") Instant now,
               @Param("availableStatus") int availableStatus,
               @Param("usedStatus") int usedStatus);

    /**
     * 关单退回：used → available（清空订单绑定）。
     * {@code clearAutomatically} 同 {@link #casUse}：退回后重读必须拿到新状态/新计数。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE CouponRecord r
            SET r.status = :availableStatus, r.usedOrderId = NULL, r.usedTime = NULL
            WHERE r.id = :id AND r.status = :usedStatus
            """)
    int casRelease(@Param("id") Long id,
                   @Param("usedStatus") int usedStatus,
                   @Param("availableStatus") int availableStatus);

    /** 过期翻转（定时任务）：available 且已过期 → expired。 */
    @Modifying
    @Query("""
            UPDATE CouponRecord r
            SET r.status = :expiredStatus
            WHERE r.status = :availableStatus AND r.expireAt < :now
            """)
    int expireDue(@Param("now") Instant now,
                  @Param("availableStatus") int availableStatus,
                  @Param("expiredStatus") int expiredStatus);

    /**
     * 核销未确认候选（P0-G 兜底回收扫描）：status=used 且 核销时间（{@code used_time}，即 deducted_at）
     * 早于阈值。是否真的回收还要回查订单（{@code DeductedOrderVerifier}）确认订单已不存在/已取消/已关闭。
     */
    List<CouponRecord> findByStatusAndUsedTimeLessThanEqual(int status, Instant threshold);
}
