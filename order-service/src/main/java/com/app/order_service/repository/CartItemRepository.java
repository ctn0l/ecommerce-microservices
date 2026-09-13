package com.app.order_service.repository;

import com.app.order_service.model.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findAllByUserIdOrderById(Long userId);
    Optional<CartItem> findByUserIdAndProductId(Long userId, Long productId);
    long countByUserId(Long userId);
    void deleteAllByUserId(Long userId);
}
