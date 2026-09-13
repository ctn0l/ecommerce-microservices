package com.app.order_service.client;

import java.util.List;

public record StockRequest(List<Item> items) {
    public record Item(Long productId, Integer quantity) {}
}
