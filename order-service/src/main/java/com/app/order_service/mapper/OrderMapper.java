package com.app.order_service.mapper;

import com.app.order_service.dto.OrderItemDTO;
import com.app.order_service.dto.OrderResponse;
import com.app.order_service.model.Order;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
public class OrderMapper {
    public OrderResponse toResponse(Order order) {
        if (order == null) return null;
        return new OrderResponse(order.getId(), order.getCheckoutId(), order.getUserId(),
                order.getTotalAmount(), order.getStatus(), order.getPhase(), order.getFailureReason(),
                order.getItems().stream().map(item -> new OrderItemDTO(item.getId(), item.getProductId(),
                        item.getProductName(), item.getQuantity(), item.getUnitPrice(),
                        item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))).toList(),
                order.getCreatedAt(), order.getUpdatedAt());
    }
}
