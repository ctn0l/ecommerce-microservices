package com.app.order_service.mapper;

import com.app.order_service.dto.CartItemResponse;
import com.app.order_service.model.CartItem;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
public class CartItemMapper {
    public CartItemResponse toResponse(CartItem item) {
        if (item == null) return null;
        return new CartItemResponse(item.getId(), item.getProductId(), item.getProductName(),
                item.getQuantity(), item.getUnitPrice(),
                item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
    }
}
