package com.app.order_service.dto;

import java.math.BigDecimal;

public record OrderItemDTO(Long id, Long productId, String productName,
                           Integer quantity, BigDecimal unitPrice, BigDecimal subtotal) {}
