package com.app.product_service.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProductRequestValidationTest {

    private static AutoCloseable validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        var factory = Validation.buildDefaultValidatorFactory();
        validatorFactory = factory;
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() throws Exception {
        validatorFactory.close();
    }

    @Test
    void acceptsValidRequest() {
        assertThat(validator.validate(validRequest(new BigDecimal("999.99"), 10)))
                .isEmpty();
    }

    @Test
    void rejectsMissingRequiredFields() {
        ProductRequest request = new ProductRequest("", null, null, null, " ", null);

        Set<ConstraintViolation<ProductRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("name", "price", "stockQuantity", "category");
    }

    @Test
    void rejectsNegativePriceAndStockQuantity() {
        ProductRequest request = validRequest(new BigDecimal("-0.01"), -1);

        Set<ConstraintViolation<ProductRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("price", "stockQuantity");
    }

    @Test
    void rejectsPriceOutsideConfiguredPrecision() {
        ProductRequest request = validRequest(new BigDecimal("12345678901.999"), 10);

        Set<ConstraintViolation<ProductRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("price");
    }

    @Test
    void rejectsFieldsThatExceedMaximumLength() {
        ProductRequest request = new ProductRequest(
                "n".repeat(151),
                "d".repeat(2001),
                new BigDecimal("999.99"),
                10,
                "c".repeat(101),
                "i".repeat(2049)
        );

        Set<ConstraintViolation<ProductRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("name", "description", "category", "imageUrl");
    }

    private ProductRequest validRequest(BigDecimal price, Integer stockQuantity) {
        return new ProductRequest(
                "Notebook",
                "Notebook per uso professionale",
                price,
                stockQuantity,
                "Elettronica",
                "https://example.com/notebook.jpg"
        );
    }
}
