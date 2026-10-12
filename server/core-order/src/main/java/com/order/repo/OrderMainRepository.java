package com.order.repo;

import com.order.domain.OrderMain;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderMainRepository extends JpaRepository<OrderMain, Long>, JpaSpecificationExecutor<OrderMain> {

    Optional<OrderMain> findByOrderNo(String orderNo);

    List<OrderMain> findByParentOrderNo(String parentOrderNo);

    /** 父单列表（拆单父单聚合展示；parent_order_no IS NULL）。 */
    Page<OrderMain> findByUserIdAndParentOrderNoIsNull(Long userId, Pageable pageable);

    Page<OrderMain> findByUserIdAndOrderStatusAndParentOrderNoIsNull(Long userId, int orderStatus, Pageable pageable);

    /** 超时关单扫描：待付款且已过 pay_deadline（只扫父单，子单随父级联）。 */
    List<OrderMain> findByOrderStatusAndPayDeadlineBeforeAndParentOrderNoIsNull(int orderStatus, Instant time);

    /**
     * 状态 CAS（version 乐观锁 + 白名单前置校验）：并发关单/支付回调/发货只允许一方成功。
     *
     * @return 命中行数；0 = 版本或状态已被并发变更，调用方重读裁决
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderMain o SET o.orderStatus = :toStatus, o.version = o.version + 1, o.updatedAt = :now "
            + "WHERE o.id = :id AND o.version = :version AND o.orderStatus = :fromStatus")
    int casUpdateStatus(@Param("id") Long id,
                        @Param("fromStatus") int fromStatus,
                        @Param("toStatus") int toStatus,
                        @Param("version") int version,
                        @Param("now") Instant now);

    /**
     * 回填支付信息（仅 CAS 流转成功的胜方调用，故不做二次版本校验）。
     * 父单与子单分别回填，保证父子展示一致。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderMain o SET o.payType = :payType, o.payTime = :payTime, o.updatedAt = :now WHERE o.id = :id")
    int fillPayInfo(@Param("id") Long id, @Param("payType") Integer payType,
                    @Param("payTime") Instant payTime, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderMain o SET o.payType = :payType, o.payTime = :payTime, o.updatedAt = :now "
            + "WHERE o.parentOrderNo = :parentOrderNo")
    int fillPayInfoByParent(@Param("parentOrderNo") String parentOrderNo, @Param("payType") Integer payType,
                            @Param("payTime") Instant payTime, @Param("now") Instant now);

    /** 回填关闭/取消原因（timeout/manual/refund）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderMain o SET o.closeReason = :reason, o.updatedAt = :now WHERE o.id = :id")
    int fillCloseReason(@Param("id") Long id, @Param("reason") String reason, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderMain o SET o.closeReason = :reason, o.updatedAt = :now WHERE o.parentOrderNo = :parentOrderNo")
    int fillCloseReasonByParent(@Param("parentOrderNo") String parentOrderNo,
                                @Param("reason") String reason, @Param("now") Instant now);

    /** 回填发货/收货时间（仅流转胜方调用）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderMain o SET o.deliveryTime = :now, o.updatedAt = :now WHERE o.id = :id")
    int fillDeliveryTime(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderMain o SET o.deliveryTime = :now, o.updatedAt = :now WHERE o.parentOrderNo = :parentOrderNo")
    int fillDeliveryTimeByParent(@Param("parentOrderNo") String parentOrderNo, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderMain o SET o.receiveTime = :now, o.updatedAt = :now WHERE o.id = :id")
    int fillReceiveTime(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OrderMain o SET o.receiveTime = :now, o.updatedAt = :now WHERE o.parentOrderNo = :parentOrderNo")
    int fillReceiveTimeByParent(@Param("parentOrderNo") String parentOrderNo, @Param("now") Instant now);
}
