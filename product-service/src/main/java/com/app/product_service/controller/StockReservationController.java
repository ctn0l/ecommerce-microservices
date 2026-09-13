package com.app.product_service.controller;

import com.app.product_service.dto.StockReservationRequest;
import com.app.product_service.dto.StockReservationResponse;
import com.app.product_service.service.StockReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import java.util.UUID;

@RestController
@RequestMapping("/api/stock-reservations")
@RequiredArgsConstructor
public class StockReservationController {
    private final StockReservationService service;

    @PutMapping("/{checkoutId}")
    public StockReservationResponse reserve(@PathVariable UUID checkoutId,
                                            @Valid @RequestBody StockReservationRequest request) {
        return service.reserve(checkoutId, request);
    }

    @PostMapping("/{checkoutId}/confirm")
    public StockReservationResponse confirm(@PathVariable UUID checkoutId) {
        return service.confirm(checkoutId);
    }

    @PostMapping("/{checkoutId}/release")
    public StockReservationResponse release(@PathVariable UUID checkoutId) {
        return service.release(checkoutId);
    }
}
