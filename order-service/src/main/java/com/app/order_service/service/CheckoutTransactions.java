package com.app.order_service.service;

import com.app.order_service.client.ProductClient;
import com.app.order_service.client.StockRequest;
import com.app.order_service.client.DependencyUnavailableException;
import com.app.order_service.client.ReservationRejectedException;
import com.app.order_service.model.Order;
import com.app.order_service.model.OrderItem;
import com.app.order_service.model.enums.CheckoutPhase;
import com.app.order_service.model.enums.OrderStatus;
import com.app.order_service.repository.CartItemRepository;
import com.app.order_service.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutTransactions {
    private final OrderRepository orders;
    private final CartItemRepository cartItems;
    private final CartTransactions carts;
    private final ProductClient products;

    @Transactional
    public Long begin(Long userId, UUID key) {
        var cart = carts.lock(userId);
        var existing = orders.findByUserIdAndIdempotencyKey(userId, key);
        if (existing.isPresent()) return existing.get().getId();
        carts.ensureEditable(cart);
        var items = cartItems.findAllByUserIdOrderById(userId);
        if (items.isEmpty()) throw conflict("Cart is empty");

        Order order = new Order();
        order.setUserId(userId);
        order.setIdempotencyKey(key);
        BigDecimal total = BigDecimal.ZERO;
        for (var item : items) {
            OrderItem line = new OrderItem();
            line.setOrder(order);
            line.setProductId(item.getProductId());
            line.setProductName(item.getProductName());
            line.setQuantity(item.getQuantity());
            line.setUnitPrice(item.getUnitPrice());
            order.getItems().add(line);
            total = total.add(item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        if (total.compareTo(new BigDecimal("9999999999.99")) > 0) throw conflict("Order total exceeds supported amount");
        order.setTotalAmount(total);
        cart.setActiveCheckout(order.getCheckoutId());
        return orders.saveAndFlush(order).getId();
    }

    @Transactional
    public boolean step(Long id) {
        // A DB row lock serializes HTTP callers and recovery workers across application instances.
        // Each phase commits separately. Remote calls have bounded timeouts and are idempotent.
        Order order = orders.findForUpdate(id).orElseThrow();
        if (terminal(order) || order.getNextAttemptAt().isAfter(now())) return false;
        try {
            switch (order.getPhase()) {
                case RESERVING -> reserve(order);
                case CONFIRMING -> {
                    products.confirm(order.getCheckoutId());
                    var cart = carts.lock(order.getUserId());
                    requireOwnership(order, cart.getActiveCheckout());
                    cartItems.deleteAllByUserId(order.getUserId());
                    cart.setActiveCheckout(null);
                    order.setStatus(OrderStatus.CONFIRMED);
                    order.setPhase(CheckoutPhase.COMPLETED);
                }
                case RELEASING -> {
                    products.release(order.getCheckoutId());
                    var cart = carts.lock(order.getUserId());
                    requireOwnership(order, cart.getActiveCheckout());
                    cart.setActiveCheckout(null);
                    order.setStatus(OrderStatus.CANCELLED);
                    order.setPhase(CheckoutPhase.FAILED);
                }
                default -> { return false; }
            }
            order.setAttempts(0);
            order.setNextAttemptAt(now());
            log.info("Checkout {} phase {}", order.getCheckoutId(), order.getPhase());
            return !terminal(order);
        } catch (DependencyUnavailableException exception) {
            int attempts = Math.min(order.getAttempts() + 1, 30);
            order.setAttempts(attempts);
            order.setNextAttemptAt(now().plusSeconds(Math.min(60, 1L << Math.min(attempts, 6))));
            log.warn("Checkout {} phase {} deferred; attempt {}", order.getCheckoutId(), order.getPhase(), attempts);
            return false;
        }
    }

    @Transactional
    public void cancel(Long id, Long userId) {
        Order order = orders.findForUpdate(id).filter(o -> o.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        if (order.getPhase() == CheckoutPhase.FAILED || order.getPhase() == CheckoutPhase.RELEASING) return;
        if (order.getPhase() != CheckoutPhase.RESERVING) throw conflict("Checkout can no longer be cancelled");
        order.setPhase(CheckoutPhase.RELEASING);
        order.setFailureReason("Checkout cancelled by user");
        order.setNextAttemptAt(now());
    }

    private void reserve(Order order) {
        var request = new StockRequest(order.getItems().stream()
                .map(item -> new StockRequest.Item(item.getProductId(), item.getQuantity())).toList());
        try {
            products.reserve(order.getCheckoutId(), request);
            order.setPhase(CheckoutPhase.CONFIRMING);
        } catch (ReservationRejectedException exception) {
            order.setPhase(CheckoutPhase.RELEASING);
            order.setFailureReason(exception.getMessage());
        }
    }

    private void requireOwnership(Order order, UUID activeCheckout) {
        if (!order.getCheckoutId().equals(activeCheckout)) {
            throw new IllegalStateException("Checkout does not own its cart: " + order.getCheckoutId());
        }
    }

    private boolean terminal(Order order) {
        return order.getPhase() == CheckoutPhase.COMPLETED || order.getPhase() == CheckoutPhase.FAILED;
    }

    private Instant now() { return Instant.now(); }

    private ResponseStatusException conflict(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
