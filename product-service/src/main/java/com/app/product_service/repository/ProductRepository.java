package com.app.product_service.repository;

import com.app.product_service.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM stock_reservation_items i
                JOIN stock_reservations r ON r.checkout_id = i.checkout_id
                WHERE i.product_id = :id AND r.status = 'RESERVED'
            )
            """, nativeQuery = true)
    boolean hasActiveReservations(@Param("id") Long id);

    List<Product> findAllByActiveTrue();

    Optional<Product> findByIdAndActiveTrue(Long id);

    @Query("""
            SELECT p
            FROM Product p
            WHERE p.active = true
              AND p.stockQuantity > 0
              AND LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
            """)
    List<Product> searchProducts(@Param("keyword") String keyword);
}
