package com.promotion.repo;

import com.promotion.domain.CouponTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Optional;

/**
 * 券模板仓储：超领/核销计数一律条件更新（原子 CAS），并发下不会超发。
 *
 * <p>领取链路的并发口径见 {@link #findByIdForReceive}（悲观锁临界区）与 {@link #reserveStock}
 * （条件更新：库存/时间窗/上线状态）。两者在同一个模板行上串行化，未新增锁竞争面。</p>
 */
public interface CouponTemplateRepository extends JpaRepository<CouponTemplate, Long> {

    /**
     * 领取前<b>悲观加锁</b>读取模板（{@code SELECT ... FOR UPDATE}），开启领取临界区。
     *
     * <p><b>为什么需要它（并发防超限领的核心）</b>：每人限领是「该用户在该模板下已有几张券」这种
     * <b>跨行计数</b>，无法像库存那样塞进单行的条件更新里；能保证原子性的办法只有两种——
     * 计数表 + 条件更新，或把「查计数 → 判断 → 写记录」整体放进一个互斥临界区。
     * 本模块选后者：同一模板的并发领取在本行排他锁上串行，等待锁的请求恢复后执行的计数查询
     * 必然看到前一个事务已提交的券记录（配合 {@code CouponService.receive} 的
     * {@code READ_COMMITTED} 隔离级别——RR 下事务快照会停留在加锁之前，仍会读到旧计数）。</p>
     *
     * <p><b>被否决的替代方案</b>：{@code UPDATE ... WHERE (SELECT COUNT(*) FROM coupon_record ...) < perLimit}
     * 单语句形式<b>不能</b>保证并发正确性——子查询按<b>语句快照</b>求值（求值发生在等待行锁之前），
     * 并发请求会一起读到 {@code COUNT=0} 全部通过（实测 H2 下 16 并发有 10 个同时超发）。</p>
     *
     * <p>必须是<b>事务内</b>调用（否则 {@code FOR UPDATE} 立即释放锁，临界区失效）。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM CouponTemplate t WHERE t.id = :id")
    Optional<CouponTemplate> findByIdForReceive(@Param("id") Long id);

    /**
     * 领取预占（防超领核心）：仅当 生效 + 未超发放总量 + 时间窗内 才自增已领数。
     * 返回 0 表示已领完/未开始/已结束/已下线（调用方按预检结果细化错误码）。
     *
     * <p><b>不含每人限领条件</b>——限领判定在 {@link #findByIdForReceive} 的临界区内完成
     * （计数是跨行统计，单行条件更新表达不了）；本方法负责「总量」这一维度，
     * 管理端定向发放 {@code distribute} 与用户自助领取 {@code receive} 共用。</p>
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
