package com.payment.repo;

import com.payment.domain.PaymentNotifyLog;
import org.springframework.data.jpa.repository.JpaRepository;

/** 回调通知日志仓储（唯一键 {@code notify_id} 去重，见实体注释）。 */
public interface PaymentNotifyLogRepository extends JpaRepository<PaymentNotifyLog, Long> {

    boolean existsByNotifyId(String notifyId);

    long countByNotifyId(String notifyId);
}
