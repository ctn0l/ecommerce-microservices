package com.app.product_service.service;

import com.app.product_service.dto.StockReservationRequest;
import com.app.product_service.dto.ProductRequest;
import com.app.product_service.model.Product;
import com.app.product_service.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class StockReservationIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");
    @Autowired StockReservationService service;
    @Autowired ProductService productService;
    @Autowired ProductRepository products;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void clean() { jdbc.execute("TRUNCATE stock_reservation_items, stock_reservations, products RESTART IDENTITY CASCADE"); }

    @Test
    void reservesAndConfirmsOnlyOnce() {
        Long product = product(5);
        UUID key = UUID.randomUUID();
        var request = request(product, 2);
        service.reserve(key, request);
        service.reserve(key, request);
        assertThat(stock(product)).isEqualTo(3);
        assertThat(service.confirm(key).status()).isEqualTo("CONFIRMED");
        service.confirm(key);
        assertThat(service.reserve(key, request).status()).isEqualTo("CONFIRMED");
        assertThat(stock(product)).isEqualTo(3);
        assertThatThrownBy(() -> service.release(key)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void releasesExactlyOnceEvenAfterProductDeactivation() {
        Long product = product(5);
        UUID key = UUID.randomUUID();
        service.reserve(key, request(product, 2));
        productService.deleteProduct(product);
        service.release(key);
        service.release(key);
        assertThat(stock(product)).isEqualTo(5);
        assertThatThrownBy(() -> service.reserve(key, request(product, 2))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.confirm(key)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void releaseBeforeReserveFencesLateRequests() {
        Long product = product(5);
        UUID key = UUID.randomUUID();
        service.release(key);
        assertThatThrownBy(() -> service.reserve(key, request(product, 2))).isInstanceOf(ResponseStatusException.class);
        assertThat(stock(product)).isEqualTo(5);
    }

    @Test
    void rollsBackEntireBasketWhenOneProductIsUnavailable() {
        Long first = product(5);
        Long second = product(0);
        UUID key = UUID.randomUUID();
        var request = new StockReservationRequest(List.of(new StockReservationRequest.Item(first, 2),
                new StockReservationRequest.Item(second, 1)));
        assertThatThrownBy(() -> service.reserve(key, request)).isInstanceOf(ResponseStatusException.class);
        assertThat(stock(first)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_reservations", Long.class)).isZero();
    }

    @Test
    void rejectsMissingAndInactiveProducts() {
        assertThatThrownBy(() -> service.reserve(UUID.randomUUID(), request(99L, 1)))
                .isInstanceOf(ResponseStatusException.class);
        Long id = product(5);
        productService.deleteProduct(id);
        assertThatThrownBy(() -> service.reserve(UUID.randomUUID(), request(id, 1)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(stock(id)).isEqualTo(5);
    }

    @Test
    void rejectsReusingKeyWithDifferentItemsAndDuplicateProducts() {
        Long id = product(5);
        UUID key = UUID.randomUUID();
        service.reserve(key, request(id, 1));
        assertThatThrownBy(() -> service.reserve(key, request(id, 2))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.reserve(UUID.randomUUID(), new StockReservationRequest(List.of(
                new StockReservationRequest.Item(id, 1), new StockReservationRequest.Item(id, 1)))))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(stock(id)).isEqualTo(4);
    }

    @Test
    void onlyOneConcurrentBuyerGetsLastPiece() throws Exception {
        Long product = product(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> buy = () -> {
                start.await();
                try { service.reserve(UUID.randomUUID(), request(product, 1)); return true; }
                catch (ResponseStatusException exception) { return false; }
            };
            var first = executor.submit(buy);
            var second = executor.submit(buy);
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(stock(product)).isZero();
    }

    @Test
    void simultaneousRetriesConsumeStockOnlyOnce() throws Exception {
        Long product = product(5);
        UUID key = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<String> buy = () -> { start.await(); return service.reserve(key, request(product, 2)).status(); };
            var first = executor.submit(buy);
            var second = executor.submit(buy);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("RESERVED");
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo("RESERVED");
        }
        assertThat(stock(product)).isEqualTo(3);
    }

    @Test
    void cannotOverwriteStockWhileReservationIsPending() {
        Long product = product(5);
        UUID key = UUID.randomUUID();
        service.reserve(key, request(product, 2));
        var update = new ProductRequest("Changed", null, BigDecimal.TEN, 20, "Test", null);
        assertThatThrownBy(() -> productService.updateProduct(product, update)).isInstanceOf(ResponseStatusException.class);
        service.release(key);
        productService.updateProduct(product, update);
        assertThat(stock(product)).isEqualTo(20);
    }

    @Test
    void rejectsMalformedHttpRequests() throws Exception {
        for (String body : List.of("{\"items\":[]}", "{\"items\":[null]}",
                "{\"items\":[{\"productId\":1,\"quantity\":0}]}")) {
            mvc.perform(put("/api/stock-reservations/" + UUID.randomUUID())
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/stock-reservations/invalid").contentType("application/json")
                .content("{\"items\":[{\"productId\":1,\"quantity\":1}]}"))
                .andExpect(status().isBadRequest());
    }

    private Long product(int stock) {
        Product product = new Product();
        product.setName("Keyboard");
        product.setCategory("Test");
        product.setPrice(new BigDecimal("39.99"));
        product.setStockQuantity(stock);
        return products.saveAndFlush(product).getId();
    }

    private int stock(Long id) { return products.findById(id).orElseThrow().getStockQuantity(); }
    private StockReservationRequest request(Long id, int quantity) {
        return new StockReservationRequest(List.of(new StockReservationRequest.Item(id, quantity)));
    }
}
