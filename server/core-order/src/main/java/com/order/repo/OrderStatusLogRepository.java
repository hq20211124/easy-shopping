package com.order.repo;

import com.order.domain.OrderStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderStatusLogRepository extends JpaRepository<OrderStatusLog, Long> {

    List<OrderStatusLog> findByOrderIdOrderByCreatedAtAsc(Long orderId);
}
