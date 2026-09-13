package com.app.product_service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record StockReservationRequest(
        @NotEmpty @Size(max = 100) List<@NotNull @Valid Item> items
) {
    public record Item(@NotNull @Positive Long productId, @NotNull @Positive Integer quantity) {}
}
