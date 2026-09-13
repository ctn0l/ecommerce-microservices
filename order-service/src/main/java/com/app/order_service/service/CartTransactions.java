package com.app.order_service.service;

import com.app.order_service.client.ProductDetails;
import com.app.order_service.dto.CartItemRequest;
import com.app.order_service.model.Cart;
import com.app.order_service.model.CartItem;
import com.app.order_service.repository.CartItemRepository;
import com.app.order_service.repository.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CartTransactions {
    private final CartRepository carts;
    private final CartItemRepository items;
    private final JdbcTemplate jdbc;

    @Transactional
    public Cart lock(Long userId) {
        jdbc.update("INSERT INTO carts(user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING", userId);
        return carts.findForUpdate(userId).orElseThrow();
    }

    @Transactional
    public void add(Long userId, CartItemRequest request, ProductDetails product) {
        ensureEditable(lock(userId));
        if (!Boolean.TRUE.equals(product.active())) throw conflict("Product is not active");
        CartItem item = items.findByUserIdAndProductId(userId, request.productId()).orElseGet(() -> {
            if (items.countByUserId(userId) >= 100) throw conflict("Cart cannot contain more than 100 products");
            CartItem created = new CartItem();
            created.setUserId(userId);
            created.setProductId(request.productId());
            created.setQuantity(0);
            return created;
        });
        long quantity = (long) item.getQuantity() + request.quantity();
        if (quantity > product.stockQuantity()) throw conflict("Requested quantity exceeds available stock");
        item.setQuantity((int) quantity);
        item.setProductName(product.name());
        item.setUnitPrice(product.price());
        items.saveAndFlush(item);
    }

    @Transactional
    public boolean remove(Long userId, Long productId) {
        ensureEditable(lock(userId));
        return items.findByUserIdAndProductId(userId, productId).map(item -> {
            items.delete(item);
            return true;
        }).orElse(false);
    }

    public void ensureEditable(Cart cart) {
        if (cart.getActiveCheckout() != null) throw conflict("Cart has a checkout in progress");
    }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
