package com.app.order_service.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Component
public class ProductClient {
    private final RestClient client;

    public ProductClient(@Qualifier("productRestClient") RestClient client) {
        this.client = client;
    }

    public ProductDetails getProduct(Long productId) {
        try {
            ProductDetails product = client.get().uri("/api/products/{id}", productId)
                    .retrieve().body(ProductDetails.class);
            if (product == null || !productId.equals(product.id()) || product.name() == null
                    || product.name().isBlank() || product.name().length() > 150 || product.price() == null
                    || product.price().signum() < 0 || product.price().scale() > 2
                    || product.price().compareTo(new java.math.BigDecimal("9999999999.99")) > 0
                    || product.stockQuantity() == null || product.stockQuantity() < 0 || product.active() == null) {
                throw new DependencyUnavailableException("Product service", null);
            }
            return product;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
            }
            throw new DependencyUnavailableException("Product service", exception);
        } catch (RestClientException exception) {
            throw new DependencyUnavailableException("Product service", exception);
        }
    }

    public void reserve(UUID checkoutId, StockRequest request) {
        try {
            StockResponse response = client.put().uri("/api/stock-reservations/{id}", checkoutId)
                    .body(request).retrieve().body(StockResponse.class);
            validate(response, checkoutId, "RESERVED", "CONFIRMED");
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404 || exception.getStatusCode().value() == 409) {
                throw new ReservationRejectedException();
            }
            throw new DependencyUnavailableException("Product service", exception);
        } catch (RestClientException exception) {
            throw new DependencyUnavailableException("Product service", exception);
        }
    }

    public void confirm(UUID checkoutId) {
        transition(checkoutId, "confirm", "CONFIRMED");
    }

    public void release(UUID checkoutId) {
        transition(checkoutId, "release", "RELEASED");
    }

    private void transition(UUID checkoutId, String action, String expected) {
        try {
            StockResponse response = client.post().uri("/api/stock-reservations/{id}/{action}", checkoutId, action)
                    .retrieve().body(StockResponse.class);
            validate(response, checkoutId, expected, expected);
        } catch (RestClientException exception) {
            throw new DependencyUnavailableException("Product service", exception);
        }
    }

    private void validate(StockResponse response, UUID id, String expected, String alternative) {
        if (response == null || !id.equals(response.checkoutId())
                || !(expected.equals(response.status()) || alternative.equals(response.status()))) {
            throw new DependencyUnavailableException("Product service", null);
        }
    }
}
