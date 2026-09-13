package com.app.order_service.service;

import com.app.order_service.client.ProductClient;
import com.app.order_service.client.UserClient;
import com.app.order_service.dto.CartItemRequest;
import com.app.order_service.dto.CartItemResponse;
import com.app.order_service.mapper.CartItemMapper;
import com.app.order_service.repository.CartItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CartService {
    private final UserClient users;
    private final ProductClient products;
    private final CartTransactions transactions;
    private final CartItemRepository items;
    private final CartItemMapper mapper;

    public void addToCart(Long userId, CartItemRequest request) {
        users.requireUser(userId);
        var product = products.getProduct(request.productId());
        transactions.add(userId, request, product);
    }

    public boolean removeFromCart(Long userId, Long productId) {
        return transactions.remove(userId, productId);
    }

    @Transactional(readOnly = true)
    public List<CartItemResponse> getCart(Long userId) {
        return items.findAllByUserIdOrderById(userId).stream().map(mapper::toResponse).toList();
    }
}
