package com.inventory.repo;

import com.inventory.domain.ReservationStatus;
import com.inventory.domain.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    List<StockReservation> findByOrderIdAndStatus(Long orderId, ReservationStatus status);

    List<StockReservation> findByOrderId(Long orderId);

    boolean existsByOrderIdAndStatus(Long orderId, ReservationStatus status);

    /** 超时扫描：状态仍为 ACTIVE 且已过期的预占单。 */
    List<StockReservation> findByStatusAndExpireAtBefore(ReservationStatus status, Instant time);

    List<StockReservation> findByOrderIdInAndStatus(Collection<Long> orderIds, ReservationStatus status);
}
