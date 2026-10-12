package com.payment.repo;

import com.payment.domain.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 支付单仓储。状态流转一律走 SQL CAS（条件更新），保证并发下只成功一次；
 * {@code @Modifying} 批量更新绕过 @Version，因此 CAS 条件即并发控制手段（DEV.md 4.3）。
 */
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {

    Optional<PaymentOrder> findByOrderId(Long orderId);

    /** 对账兜底扫描：已支付但订单侧尚未确认 paid 的支付单。 */
    List<PaymentOrder> findByStatusAndOrderPaidNotifiedFalse(Integer status);

    /** 回调幂等三步之 ②：状态 CAS——仅 pending 允许置 paid（终态/已 paid 拒绝）。 */
    @Modifying
    @Query("""
            UPDATE PaymentOrder p
            SET p.status = :toStatus, p.paidAt = :now, p.thirdPartyNo = :thirdPartyNo, p.updatedAt = :now
            WHERE p.id = :id AND p.status = :fromStatus
            """)
    int casMarkPaid(@Param("id") Long id,
                    @Param("fromStatus") int fromStatus,
                    @Param("toStatus") int toStatus,
                    @Param("thirdPartyNo") String thirdPartyNo,
                    @Param("now") Instant now);

    /** 退款第一步：paid → refunding。 */
    @Modifying
    @Query("""
            UPDATE PaymentOrder p
            SET p.status = :toStatus, p.refundReason = :reason, p.updatedAt = :now
            WHERE p.id = :id AND p.status = :fromStatus
            """)
    int casStartRefund(@Param("id") Long id,
                       @Param("fromStatus") int fromStatus,
                       @Param("toStatus") int toStatus,
                       @Param("reason") String reason,
                       @Param("now") Instant now);

    /** 退款第二步：refunding → refunded（P0 mock 渠道同步出账）。 */
    @Modifying
    @Query("""
            UPDATE PaymentOrder p
            SET p.status = :toStatus, p.refundAt = :now, p.updatedAt = :now
            WHERE p.id = :id AND p.status = :fromStatus
            """)
    int casMarkRefunded(@Param("id") Long id,
                        @Param("fromStatus") int fromStatus,
                        @Param("toStatus") int toStatus,
                        @Param("now") Instant now);

    /** 订单侧确认标记（对账任务补偿成功后置位，防重复补偿）。调用方可能在事务外（回调后置/对账任务），故方法级声明写事务。 */
    @Modifying
    @Transactional
    @Query("UPDATE PaymentOrder p SET p.orderPaidNotified = true WHERE p.id = :id AND p.orderPaidNotified = false")
    int markOrderPaidNotified(@Param("id") Long id);
}
