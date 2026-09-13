package com.app.order_service.repository;

import com.app.order_service.model.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByUserIdAndIdempotencyKey(Long userId, UUID key);
    Optional<Order> findByIdAndUserId(Long id, Long userId);
    List<Order> findAllByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findForUpdate(@Param("id") Long id);

    @Query(value = """
            SELECT id FROM orders
            WHERE phase IN ('RESERVING','CONFIRMING','RELEASING') AND next_attempt_at <= :now
            ORDER BY next_attempt_at, id LIMIT 20
            """, nativeQuery = true)
    List<Long> findRecoverable(@Param("now") Instant now);
}
