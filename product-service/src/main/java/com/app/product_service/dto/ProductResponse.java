package com.app.product_service.dto;

import java.math.BigDecimal;

public record ProductResponse(
        Long id,
        String name,
        String description,
        BigDecimal price,
        Integer stockQuantity,
        String category,
        String imageUrl,
        Boolean active
) {
}
