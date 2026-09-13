package com.app.order_service.service;

import com.app.order_service.client.UserClient;
import com.app.order_service.dto.OrderResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {
    private final UserClient users;
    private final OrderQueries queries;
    private final CheckoutTransactions transactions;
    private final CheckoutProcessor processor;

    public OrderResponse createOrder(Long userId, UUID key) {
        Long id = queries.findExisting(userId, key).orElseGet(() -> {
            users.requireUser(userId);
            return transactions.begin(userId, key);
        });
        processor.process(id);
        return queries.get(id, userId);
    }

    public OrderResponse cancel(Long id, Long userId) {
        transactions.cancel(id, userId);
        processor.process(id);
        return queries.get(id, userId);
    }
}
