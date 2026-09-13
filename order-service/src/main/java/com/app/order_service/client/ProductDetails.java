package com.app.order_service.client;

import java.math.BigDecimal;

public record ProductDetails(Long id, String name, BigDecimal price, Integer stockQuantity, Boolean active) {}
