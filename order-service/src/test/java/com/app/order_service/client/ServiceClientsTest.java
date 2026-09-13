package com.app.order_service.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import static org.assertj.core.api.Assertions.*;

class ServiceClientsTest {
    private HttpServer server;
    private ExecutorService executor;
    private UserClient users;
    private ProductClient products;
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile long delay;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
        factory.setReadTimeout(Duration.ofSeconds(1));
        RestClient client = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .requestFactory(factory).build();
        users = new UserClient(client);
        products = new ProductClient(client);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void userClientAcceptsActualUserResponseWithAdditionalFields() {
        body = "{\"id\":42,\"firstName\":\"Ada\",\"email\":\"ada@example.test\"}";
        users.requireUser(42L);
        assertThat(requests).singleElement().asString().startsWith("GET /api/users/42");
    }

    @Test
    void distinguishesMissingUserFromUnavailableServiceAndMalformedResponse() {
        status = 404;
        assertThatThrownBy(() -> users.requireUser(42L)).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
        status = 503;
        assertThatThrownBy(() -> users.requireUser(42L)).isInstanceOf(DependencyUnavailableException.class);
        status = 200;
        body = "not-json";
        assertThatThrownBy(() -> users.requireUser(42L)).isInstanceOf(DependencyUnavailableException.class);
        body = "{\"id\":99}";
        assertThatThrownBy(() -> users.requireUser(42L)).isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void productClientReadsCatalogContractAndRejectsInvalidData() {
        body = "{\"id\":10,\"name\":\"Keyboard\",\"price\":39.99,\"stockQuantity\":3,\"active\":true,\"category\":\"Test\"}";
        var product = products.getProduct(10L);
        assertThat(product.price()).isEqualByComparingTo("39.99");
        body = "{\"id\":10}";
        assertThatThrownBy(() -> products.getProduct(10L)).isInstanceOf(DependencyUnavailableException.class);
        status = 404;
        assertThatThrownBy(() -> products.getProduct(10L)).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void reservationUsesStableIdAndChecksRemoteState() {
        UUID key = UUID.randomUUID();
        body = "{\"checkoutId\":\"" + key + "\",\"status\":\"RESERVED\"}";
        products.reserve(key, new StockRequest(List.of(new StockRequest.Item(10L, 2))));
        assertThat(requests).singleElement().asString()
                .startsWith("PUT /api/stock-reservations/" + key)
                .contains("\"productId\":10", "\"quantity\":2");
        body = "{\"checkoutId\":\"" + key + "\",\"status\":\"RELEASED\"}";
        assertThatThrownBy(() -> products.reserve(key, new StockRequest(List.of(new StockRequest.Item(10L, 2)))))
                .isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void reservationBusinessRejectionDiffersFromAmbiguousFailure() {
        UUID key = UUID.randomUUID();
        var request = new StockRequest(List.of(new StockRequest.Item(10L, 1)));
        for (int code : List.of(404, 409)) {
            status = code;
            assertThatThrownBy(() -> products.reserve(key, request)).isInstanceOf(ReservationRejectedException.class);
        }
        for (int code : List.of(400, 500, 503)) {
            status = code;
            assertThatThrownBy(() -> products.reserve(key, request)).isInstanceOf(DependencyUnavailableException.class);
        }
    }

    @Test
    void confirmAndReleaseUseSeparateIdempotentOperations() {
        UUID key = UUID.randomUUID();
        body = "{\"checkoutId\":\"" + key + "\",\"status\":\"CONFIRMED\"}";
        products.confirm(key);
        body = "{\"checkoutId\":\"" + key + "\",\"status\":\"RELEASED\"}";
        products.release(key);
        assertThat(requests.get(0)).startsWith("POST /api/stock-reservations/" + key + "/confirm");
        assertThat(requests.get(1)).startsWith("POST /api/stock-reservations/" + key + "/release");
        status = 409;
        assertThatThrownBy(() -> products.confirm(key)).isInstanceOf(DependencyUnavailableException.class);
        assertThatThrownBy(() -> products.release(key)).isInstanceOf(DependencyUnavailableException.class);
    }

    @Test
    void readTimeoutBecomesTemporaryUnavailability() {
        delay = 2000;
        assertThatThrownBy(() -> users.requireUser(42L)).isInstanceOf(DependencyUnavailableException.class);
    }
}
