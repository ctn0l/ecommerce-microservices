package com.app.product_service.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank
        @Size(max = 150)
        String name,

        @Size(max = 2000)
        String description,

        @NotNull
        @PositiveOrZero
        @Digits(integer = 10, fraction = 2)
        BigDecimal price,

        @NotNull
        @PositiveOrZero
        Integer stockQuantity,

        @NotBlank
        @Size(max = 100)
        String category,

        @Size(max = 2048)
        String imageUrl
) {
}
