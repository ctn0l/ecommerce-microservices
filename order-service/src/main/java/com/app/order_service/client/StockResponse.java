package com.app.order_service.client;

import java.util.UUID;

public record StockResponse(UUID checkoutId, String status) {}
