package com.app.product_service.service;

import com.app.product_service.dto.StockReservationRequest;
import com.app.product_service.dto.StockReservationResponse;
import com.app.product_service.model.Product;
import com.app.product_service.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class StockReservationService {
    private final JdbcTemplate jdbc;
    private final ProductRepository products;

    public StockReservationResponse reserve(UUID checkoutId, StockReservationRequest request) {
        Map<Long, Integer> requested = new TreeMap<>();
        for (var item : request.items()) {
            if (requested.putIfAbsent(item.productId(), item.quantity()) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate product ID");
            }
        }
        lock(checkoutId);
        String status = status(checkoutId);
        if (status != null) {
            if (status.equals("RELEASED") || !items(checkoutId).equals(requested)) {
                throw conflict("Reservation is released or the checkout ID was reused with different items");
            }
            return new StockReservationResponse(checkoutId, status);
        }

        jdbc.update("INSERT INTO stock_reservations(checkout_id, status) VALUES (?, 'RESERVED')", checkoutId);
        // Lock in product-ID order: concurrent baskets must acquire locks in the same order.
        for (var entry : requested.entrySet()) {
            Product product = products.findByIdForUpdate(entry.getKey())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
            if (!Boolean.TRUE.equals(product.getActive()) || product.getStockQuantity() < entry.getValue()) {
                throw conflict("Product is inactive or has insufficient stock");
            }
            product.setStockQuantity(product.getStockQuantity() - entry.getValue());
            jdbc.update("INSERT INTO stock_reservation_items(checkout_id, product_id, quantity) VALUES (?, ?, ?)",
                    checkoutId, entry.getKey(), entry.getValue());
        }
        products.flush();
        return new StockReservationResponse(checkoutId, "RESERVED");
    }

    public StockReservationResponse confirm(UUID checkoutId) {
        lock(checkoutId);
        String status = status(checkoutId);
        if (status == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found");
        }
        if (status.equals("RELEASED")) {
            throw conflict("Released reservations cannot be confirmed");
        }
        jdbc.update("UPDATE stock_reservations SET status = 'CONFIRMED' WHERE checkout_id = ?", checkoutId);
        return new StockReservationResponse(checkoutId, "CONFIRMED");
    }

    public StockReservationResponse release(UUID checkoutId) {
        lock(checkoutId);
        String status = status(checkoutId);
        if ("CONFIRMED".equals(status)) {
            throw conflict("Confirmed reservations cannot be released");
        }
        if (status == null) {
            // A tombstone fences a delayed reserve request after an ambiguous network failure.
            jdbc.update("INSERT INTO stock_reservations(checkout_id, status) VALUES (?, 'RELEASED')", checkoutId);
        } else if (status.equals("RESERVED")) {
            for (var entry : items(checkoutId).entrySet()) {
                Product product = products.findByIdForUpdate(entry.getKey()).orElseThrow();
                product.setStockQuantity(Math.addExact(product.getStockQuantity(), entry.getValue()));
            }
            products.flush();
            jdbc.update("UPDATE stock_reservations SET status = 'RELEASED' WHERE checkout_id = ?", checkoutId);
        }
        return new StockReservationResponse(checkoutId, "RELEASED");
    }

    private void lock(UUID checkoutId) {
        // Transaction-scoped: serializes even the first request, before a reservation row exists.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, checkoutId.toString());
    }

    private String status(UUID checkoutId) {
        var statuses = jdbc.queryForList("SELECT status FROM stock_reservations WHERE checkout_id = ?", String.class, checkoutId);
        return statuses.isEmpty() ? null : statuses.getFirst();
    }

    private Map<Long, Integer> items(UUID checkoutId) {
        Map<Long, Integer> result = new TreeMap<>();
        jdbc.query("SELECT product_id, quantity FROM stock_reservation_items WHERE checkout_id = ? ORDER BY product_id",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getLong(1), rs.getInt(2)), checkoutId);
        return result;
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
