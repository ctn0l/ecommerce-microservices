package com.app.order_service.service;

import com.app.order_service.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "checkout.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class CheckoutRecovery {
    private final OrderRepository orders;
    private final CheckoutProcessor processor;

    @Scheduled(fixedDelayString = "${checkout.recovery.delay:5000}")
    public void recover() {
        orders.findRecoverable(Instant.now()).forEach(processor::process);
    }
}
