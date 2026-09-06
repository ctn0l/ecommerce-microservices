package com.app.product_service.mapper;

import com.app.product_service.dto.ProductRequest;
import com.app.product_service.dto.ProductResponse;
import com.app.product_service.model.Product;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMapperTest {

    private ProductMapper productMapper;

    @BeforeEach
    void setUp() {
        productMapper = new ProductMapper();
    }

    @Test
    void mapsRequestToEntityWithoutChangingServerManagedDefaults() {
        Product product = productMapper.toEntity(request("Notebook", new BigDecimal("999.99")));

        assertThat(product.getId()).isNull();
        assertThat(product.getActive()).isTrue();
        assertThat(product.getName()).isEqualTo("Notebook");
        assertThat(product.getPrice()).isEqualByComparingTo("999.99");
    }

    @Test
    void updatesAllowedFieldsWithoutChangingActiveStatus() {
        Product product = productMapper.toEntity(request("Notebook", new BigDecimal("999.99")));
        product.setActive(false);

        productMapper.updateEntity(request("Notebook Pro", new BigDecimal("1299.99")), product);

        assertThat(product.getName()).isEqualTo("Notebook Pro");
        assertThat(product.getPrice()).isEqualByComparingTo("1299.99");
        assertThat(product.getActive()).isFalse();
    }

    @Test
    void mapsEntityToResponse() {
        Product product = productMapper.toEntity(request("Notebook", new BigDecimal("999.99")));

        ProductResponse response = productMapper.toResponse(product);

        assertThat(response.name()).isEqualTo("Notebook");
        assertThat(response.category()).isEqualTo("Elettronica");
        assertThat(response.active()).isTrue();
    }

    @Test
    void handlesNullValuesAtMapperBoundary() {
        assertThat(productMapper.toEntity(null)).isNull();
        assertThat(productMapper.toResponse(null)).isNull();
    }

    private ProductRequest request(String name, BigDecimal price) {
        return new ProductRequest(
                name,
                "Notebook per uso professionale",
                price,
                10,
                "Elettronica",
                "https://example.com/notebook.jpg"
        );
    }
}
