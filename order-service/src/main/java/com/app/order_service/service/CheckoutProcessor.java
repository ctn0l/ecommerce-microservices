package com.app.order_service.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutProcessor {
    private final CheckoutTransactions transactions;

    public void process(Long id) {
        try {
            for (int step = 0; step < 3; step++) {
                if (!transactions.step(id)) break;
            }
        } catch (RuntimeException exception) {
            // The durable phase survives transaction rollback, including a crash after remote success.
            log.error("Checkout order {} could not advance; recovery will retry", id, exception);
        }
    }
}
