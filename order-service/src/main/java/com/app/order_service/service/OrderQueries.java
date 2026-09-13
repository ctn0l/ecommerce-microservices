package com.app.order_service.service;

import com.app.order_service.dto.OrderResponse;
import com.app.order_service.mapper.OrderMapper;
import com.app.order_service.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderQueries {
    private final OrderRepository orders;
    private final OrderMapper mapper;

    public Optional<Long> findExisting(Long userId, UUID key) {
        return orders.findByUserIdAndIdempotencyKey(userId, key).map(order -> order.getId());
    }

    public OrderResponse get(Long id, Long userId) {
        return orders.findByIdAndUserId(id, userId).map(mapper::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    public List<OrderResponse> list(Long userId, int page, int size) {
        return orders.findAllByUserIdOrderByIdDesc(userId, PageRequest.of(page, size))
                .stream().map(mapper::toResponse).toList();
    }
}
