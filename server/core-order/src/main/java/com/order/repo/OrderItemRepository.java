package com.order.repo;

import com.order.domain.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    List<OrderItem> findByOrderId(Long orderId);

    List<OrderItem> findByOrderNo(String orderNo);

    List<OrderItem> findByOrderIdIn(Collection<Long> orderIds);
}
