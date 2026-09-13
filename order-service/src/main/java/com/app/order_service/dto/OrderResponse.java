package com.app.order_service.dto;

import com.app.order_service.model.enums.OrderStatus;
import com.app.order_service.model.enums.CheckoutPhase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(Long id, UUID checkoutId, Long userId, BigDecimal totalAmount,
                            OrderStatus status, CheckoutPhase phase, String failureReason,
                            List<OrderItemDTO> items, Instant createdAt, Instant updatedAt) {}
