package com.app.order_service.controller;

import com.app.order_service.dto.OrderResponse;
import com.app.order_service.model.enums.OrderStatus;
import com.app.order_service.service.OrderQueries;
import com.app.order_service.service.OrderService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService service;
    private final OrderQueries queries;

    @PostMapping
    public ResponseEntity<OrderResponse> create(@RequestHeader("X-User-ID") @Positive Long userId,
                                                @RequestHeader("Idempotency-Key") UUID key) {
        var order = service.createOrder(userId, key);
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(order.id()).toUri();
        int status = switch (order.status()) {
            case CONFIRMED -> 201;
            case PENDING -> 202;
            case CANCELLED -> 409;
        };
        var response = ResponseEntity.status(status).location(location);
        if (status == 202) response.header("Retry-After", "5");
        return response.body(order);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@RequestHeader("X-User-ID") @Positive Long userId,
                             @PathVariable @Positive Long id) {
        return queries.get(id, userId);
    }

    @GetMapping
    public List<OrderResponse> list(@RequestHeader("X-User-ID") @Positive Long userId,
                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return queries.list(userId, page, size);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancel(@RequestHeader("X-User-ID") @Positive Long userId,
                                                @PathVariable @Positive Long id) {
        var response = service.cancel(id, userId);
        return ResponseEntity.status(response.status() == OrderStatus.PENDING ? 202 : 200).body(response);
    }
}
