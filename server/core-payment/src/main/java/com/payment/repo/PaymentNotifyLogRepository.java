package com.payment.repo;

import com.payment.domain.PaymentNotifyLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 回调通知日志仓储（唯一键 {@code notify_id} 去重，见实体注释）。 */
public interface PaymentNotifyLogRepository extends JpaRepository<PaymentNotifyLog, Long> {

    /** 按通知号取去重行（判重依据是 {@code processStatus}，不是「是否存在」，见 {@link PaymentNotifyLog}）。 */
    Optional<PaymentNotifyLog> findByNotifyId(String notifyId);

    boolean existsByNotifyId(String notifyId);

    long countByNotifyId(String notifyId);
}