package com.app.order_service;

import com.app.order_service.client.*;
import com.app.order_service.dto.*;
import com.app.order_service.repository.*;
import com.app.order_service.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static com.app.order_service.model.enums.CheckoutPhase.*;
import static com.app.order_service.model.enums.OrderStatus.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = "checkout.recovery.enabled=false")
@AutoConfigureMockMvc
class CheckoutIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
    @Autowired CartService carts;
    @Autowired OrderService service;
    @Autowired OrderQueries queries;
    @Autowired CheckoutTransactions transactions;
    @Autowired CheckoutProcessor processor;
    @Autowired OrderRepository orders;
    @Autowired CartItemRepository cartItems;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean UserClient users;
    @MockitoBean ProductClient products;

    @BeforeEach
    void clean() {
        jdbc.execute("DROP FUNCTION IF EXISTS fail_cart_delete() CASCADE");
        jdbc.execute("TRUNCATE order_items, orders, cart_items, carts RESTART IDENTITY CASCADE");
        when(products.getProduct(anyLong())).thenAnswer(inv ->
                new ProductDetails(inv.getArgument(0), "Keyboard", new BigDecimal("39.99"), 10, true));
    }

    @Test
    void checkoutPreservesSnapshotsClearsCartAndReplaysWithoutDependencies() {
        add(1L, 2);
        UUID key = UUID.randomUUID();
        OrderResponse result = service.createOrder(1L, key);
        assertThat(result.status()).isEqualTo(CONFIRMED);
        assertThat(result.phase()).isEqualTo(COMPLETED);
        assertThat(result.totalAmount()).isEqualByComparingTo("79.98");
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.productName()).isEqualTo("Keyboard");
            assertThat(item.unitPrice()).isEqualByComparingTo("39.99");
        });
        assertThat(carts.getCart(1L)).isEmpty();
        add(1L, 1);
        clearInvocations(users, products);
        var replay = service.createOrder(1L, key);
        assertThat(replay.id()).isEqualTo(result.id());
        assertThat(carts.getCart(1L)).hasSize(1);
        assertThat(orders.count()).isEqualTo(1);
        verifyNoInteractions(users, products);
    }

    @Test
    void emptyCartAndMissingUserDoNotCreateAnOrder() {
        assertThatThrownBy(() -> service.createOrder(1L, UUID.randomUUID())).isInstanceOf(ResponseStatusException.class);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND)).when(users).requireUser(99L);
        assertThatThrownBy(() -> service.createOrder(99L, UUID.randomUUID())).isInstanceOf(ResponseStatusException.class);
        assertThat(orders.count()).isZero();
        verify(products, never()).reserve(any(), any());
    }

    @Test
    void rejectedReservationCompensatesAndRetainsCart() {
        add(1L, 2);
        doThrow(new ReservationRejectedException()).when(products).reserve(any(), any());
        var response = service.createOrder(1L, UUID.randomUUID());
        assertThat(response.status()).isEqualTo(CANCELLED);
        assertThat(response.phase()).isEqualTo(FAILED);
        assertThat(response.failureReason()).contains("insufficient stock");
        verify(products).release(response.checkoutId());
        assertThat(carts.getCart(1L)).hasSize(1);
        assertThat(carts.removeFromCart(1L, 10L)).isTrue();
    }

    @Test
    void lostReserveResponseIsRecoveredUsingSameCheckoutId() {
        add(1L, 1);
        doThrow(new DependencyUnavailableException("Product service", null)).doNothing()
                .when(products).reserve(any(), any());
        UUID key = UUID.randomUUID();
        var pending = service.createOrder(1L, key);
        assertThat(pending.status()).isEqualTo(PENDING);
        assertThat(pending.phase()).isEqualTo(RESERVING);
        assertThatThrownBy(() -> carts.removeFromCart(1L, 10L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> add(1L, 1)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.createOrder(1L, UUID.randomUUID())).isInstanceOf(ResponseStatusException.class);
        makeDue(pending.id());
        new CheckoutRecovery(orders, processor).recover();
        assertThat(queries.get(pending.id(), 1L).status()).isEqualTo(CONFIRMED);
        verify(products, times(2)).reserve(eq(pending.checkoutId()), any());
        assertThat(carts.getCart(1L)).isEmpty();
    }

    @Test
    void lostConfirmResponseKeepsCartFrozenUntilRecovery() {
        add(1L, 1);
        doThrow(new DependencyUnavailableException("Product service", null)).doNothing()
                .when(products).confirm(any());
        var pending = service.createOrder(1L, UUID.randomUUID());
        assertThat(pending.phase()).isEqualTo(CONFIRMING);
        assertThat(carts.getCart(1L)).hasSize(1);
        assertThatThrownBy(() -> service.cancel(pending.id(), 1L)).isInstanceOf(ResponseStatusException.class);
        makeDue(pending.id());
        processor.process(pending.id());
        assertThat(queries.get(pending.id(), 1L).status()).isEqualTo(CONFIRMED);
        verify(products, times(2)).confirm(pending.checkoutId());
    }

    @Test
    void databaseFailureAfterRemoteConfirmationRollsBackLocallyAndRecovers() {
        add(1L, 1);
        jdbc.execute("""
                CREATE FUNCTION fail_cart_delete() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Simulated database failure'; END; $$
                """);
        jdbc.execute("CREATE TRIGGER fail_delete BEFORE DELETE ON cart_items FOR EACH ROW EXECUTE FUNCTION fail_cart_delete()");
        var pending = service.createOrder(1L, UUID.randomUUID());
        assertThat(pending.phase()).isEqualTo(CONFIRMING);
        assertThat(carts.getCart(1L)).hasSize(1);
        jdbc.execute("DROP FUNCTION fail_cart_delete() CASCADE");
        processor.process(pending.id());
        assertThat(queries.get(pending.id(), 1L).status()).isEqualTo(CONFIRMED);
        assertThat(carts.getCart(1L)).isEmpty();
        verify(products, times(2)).confirm(pending.checkoutId());
    }

    @Test
    void compensationFailureIsDurableAndRetried() {
        add(1L, 1);
        doThrow(new ReservationRejectedException()).when(products).reserve(any(), any());
        doThrow(new DependencyUnavailableException("Product service", null)).doNothing()
                .when(products).release(any());
        var pending = service.createOrder(1L, UUID.randomUUID());
        assertThat(pending.phase()).isEqualTo(RELEASING);
        assertThat(pending.status()).isEqualTo(PENDING);
        assertThatThrownBy(() -> carts.removeFromCart(1L, 10L)).isInstanceOf(ResponseStatusException.class);
        makeDue(pending.id());
        processor.process(pending.id());
        assertThat(queries.get(pending.id(), 1L).status()).isEqualTo(CANCELLED);
        assertThat(carts.getCart(1L)).hasSize(1);
        assertThat(carts.removeFromCart(1L, 10L)).isTrue();
    }

    @Test
    void cancelsPendingCheckoutAndReleasesAmbiguousReservation() {
        add(1L, 1);
        doThrow(new DependencyUnavailableException("Product service", null)).when(products).reserve(any(), any());
        var pending = service.createOrder(1L, UUID.randomUUID());
        var cancelled = service.cancel(pending.id(), 1L);
        assertThat(cancelled.status()).isEqualTo(CANCELLED);
        verify(products).release(pending.checkoutId());
        assertThat(service.cancel(pending.id(), 1L).status()).isEqualTo(CANCELLED);
        verify(products, times(1)).release(pending.checkoutId());
    }

    @Test
    void recoveryFindsCheckoutPersistedBeforeAnyRemoteRequest() {
        add(1L, 1);
        Long id = transactions.begin(1L, UUID.randomUUID());
        assertThat(queries.get(id, 1L).phase()).isEqualTo(RESERVING);
        verify(products, never()).reserve(any(), any());
        new CheckoutRecovery(orders, new CheckoutProcessor(transactions)).recover();
        assertThat(queries.get(id, 1L).status()).isEqualTo(CONFIRMED);
    }

    @Test
    void backoffPreventsImmediateRetries() {
        add(1L, 1);
        doThrow(new DependencyUnavailableException("Product service", null)).when(products).reserve(any(), any());
        UUID key = UUID.randomUUID();
        service.createOrder(1L, key);
        service.createOrder(1L, key);
        verify(products, times(1)).reserve(any(), any());
    }

    @Test
    void concurrentIdenticalCheckoutsCreateOneOrder() throws Exception {
        add(1L, 1);
        UUID key = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Long> buy = () -> { start.await(); return service.createOrder(1L, key).id(); };
            var first = executor.submit(buy);
            var second = executor.submit(buy);
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        assertThat(orders.count()).isEqualTo(1);
        verify(products, times(1)).reserve(any(), any());
        verify(products, times(1)).confirm(any());
    }

    @Test
    void concurrentCartAddsDoNotLoseQuantities() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Runnable add = () -> { try { start.await(); } catch (InterruptedException ex) { throw new RuntimeException(ex); } add(1L, 1); };
            var first = executor.submit(add);
            var second = executor.submit(add);
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(carts.getCart(1L)).singleElement().extracting(CartItemResponse::quantity).isEqualTo(2);
    }

    @Test
    void rejectsCartOverflowInactiveProductsAndExcessiveOrderTotal() {
        when(products.getProduct(10L)).thenReturn(new ProductDetails(10L, "Huge", BigDecimal.ONE, Integer.MAX_VALUE, true));
        add(1L, Integer.MAX_VALUE);
        assertThatThrownBy(() -> add(1L, 1)).isInstanceOf(ResponseStatusException.class);
        when(products.getProduct(11L)).thenReturn(new ProductDetails(11L, "Inactive", BigDecimal.ONE, 10, false));
        assertThatThrownBy(() -> carts.addToCart(1L, new CartItemRequest(11L, 1))).isInstanceOf(ResponseStatusException.class);
        when(products.getProduct(12L)).thenReturn(new ProductDetails(12L, "Expensive", new BigDecimal("9999999999.99"), 2, true));
        carts.addToCart(2L, new CartItemRequest(12L, 2));
        assertThatThrownBy(() -> service.createOrder(2L, UUID.randomUUID())).isInstanceOf(ResponseStatusException.class);
        assertThat(orders.count()).isZero();
    }

    @Test
    void orderReadsAndCancellationAreScopedToUser() throws Exception {
        add(1L, 1);
        var order = service.createOrder(1L, UUID.randomUUID());
        mvc.perform(get("/api/orders/" + order.id()).header("X-User-ID", 2)).andExpect(status().isNotFound());
        mvc.perform(post("/api/orders/" + order.id() + "/cancel").header("X-User-ID", 2)).andExpect(status().isNotFound());
        mvc.perform(get("/api/orders").header("X-User-ID", 2)).andExpect(content().json("[]"));
        mvc.perform(get("/api/orders").header("X-User-ID", 1))
                .andExpect(jsonPath("$[0].id").value(order.id()));
    }

    @Test
    void httpReturnsLocationForCompletedAndAcceptedCheckout() throws Exception {
        add(1L, 1);
        mvc.perform(post("/api/orders").header("X-User-ID", 1).header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isCreated()).andExpect(header().string("Location", "http://localhost/api/orders/1"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        add(1L, 1);
        doThrow(new DependencyUnavailableException("Product service", null)).when(products).reserve(any(), any());
        mvc.perform(post("/api/orders").header("X-User-ID", 1).header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isAccepted()).andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.phase").value("RESERVING"));
    }

    @Test
    void validatesHeadersIdsPaginationAndCartBody() throws Exception {
        mvc.perform(post("/api/orders").header("X-User-ID", 1)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/orders").header("X-User-ID", 1).header("Idempotency-Key", "invalid"))
                .andExpect(status().isBadRequest());
        for (String id : List.of("0", "-1", "abc")) {
            mvc.perform(get("/api/cart").header("X-User-ID", id)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/orders").header("X-User-ID", 1).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").header("X-User-ID", 1).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders/0").header("X-User-ID", 1)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/cart").header("X-User-ID", 1).contentType("application/json")
                .content("{\"productId\":1,\"quantity\":0}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/cart").header("X-User-ID", 1).contentType("application/json")
                .content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void cartHttpCrudWorksWithoutRemoteReadsForRemoval() throws Exception {
        mvc.perform(post("/api/cart").header("X-User-ID", 1).contentType("application/json")
                .content("{\"productId\":10,\"quantity\":2}")).andExpect(status().isCreated());
        mvc.perform(get("/api/cart").header("X-User-ID", 1)).andExpect(jsonPath("$[0].subtotal").value(79.98));
        clearInvocations(users, products);
        mvc.perform(delete("/api/cart/items/10").header("X-User-ID", 1)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/cart/items/10").header("X-User-ID", 1)).andExpect(status().isNotFound());
        verifyNoInteractions(users, products);
    }

    private void add(Long user, int quantity) { carts.addToCart(user, new CartItemRequest(10L, quantity)); }
    private void makeDue(Long id) {
        jdbc.update("UPDATE orders SET next_attempt_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)), id);
    }
}
