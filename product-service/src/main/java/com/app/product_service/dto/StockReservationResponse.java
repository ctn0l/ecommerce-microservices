package com.app.product_service.dto;

import java.util.UUID;

public record StockReservationResponse(UUID checkoutId, String status) {}
