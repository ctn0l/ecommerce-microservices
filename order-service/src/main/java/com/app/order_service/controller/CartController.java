package com.app.order_service.controller;

import com.app.order_service.dto.CartItemRequest;
import com.app.order_service.dto.CartItemResponse;
import com.app.order_service.service.CartService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.List;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {
    private final CartService service;

    @PostMapping
    public ResponseEntity<Void> add(@RequestHeader("X-User-ID") @Positive Long userId,
                                    @Valid @RequestBody CartItemRequest request) {
        service.addToCart(userId, request);
        return ResponseEntity.status(201).build();
    }

    @GetMapping
    public List<CartItemResponse> get(@RequestHeader("X-User-ID") @Positive Long userId) {
        return service.getCart(userId);
    }

    @DeleteMapping("/items/{productId}")
    public ResponseEntity<Void> remove(@RequestHeader("X-User-ID") @Positive Long userId,
                                       @PathVariable @Positive Long productId) {
        return service.removeFromCart(userId, productId)
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
