package com.app.product_service.service;

import com.app.product_service.dto.ProductRequest;
import com.app.product_service.dto.ProductResponse;
import com.app.product_service.mapper.ProductMapper;
import com.app.product_service.model.Product;
import com.app.product_service.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    private ProductMapper productMapper;
    private ProductService productService;

    @BeforeEach
    void setUp() {
        productMapper = new ProductMapper();
        productService = new ProductService(productRepository, productMapper);
    }

    @Test
    void createsProductFromRequestAndReturnsResponse() {
        when(productRepository.saveAndFlush(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse response = productService.createProduct(
                request("Notebook", new BigDecimal("999.99"))
        );

        assertThat(response.name()).isEqualTo("Notebook");
        assertThat(response.price()).isEqualByComparingTo("999.99");
        assertThat(response.active()).isTrue();
        verify(productRepository).saveAndFlush(any(Product.class));
    }

    @Test
    void returnsMappedActiveProducts() {
        Product product = productMapper.toEntity(request("Notebook", new BigDecimal("999.99")));
        when(productRepository.findAllByActiveTrue()).thenReturn(List.of(product));

        List<ProductResponse> responses = productService.fetchAllProducts();

        assertThat(responses)
                .singleElement()
                .extracting(ProductResponse::name)
                .isEqualTo("Notebook");
    }

    @Test
    void returnsProductWhenItExistsAndIsActive() {
        Product product = productMapper.toEntity(request("Notebook", new BigDecimal("999.99")));
        when(productRepository.findByIdAndActiveTrue(1L)).thenReturn(Optional.of(product));

        Optional<ProductResponse> response = productService.fetchProduct(1L);

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().name()).isEqualTo("Notebook");
    }

    @Test
    void returnsEmptyWhenProductIsMissingOrInactive() {
        when(productRepository.findByIdAndActiveTrue(99L)).thenReturn(Optional.empty());

        assertThat(productService.fetchProduct(99L)).isEmpty();
    }

    @Test
    void searchesProductsByNormalizedKeyword() {
        Product product = productMapper.toEntity(request("Notebook", new BigDecimal("999.99")));
        when(productRepository.searchProducts("note")).thenReturn(List.of(product));

        List<ProductResponse> responses = productService.searchProducts("  note  ");

        assertThat(responses)
                .singleElement()
                .extracting(ProductResponse::name)
                .isEqualTo("Notebook");
    }

    @Test
    void searchesAllAvailableProductsWhenKeywordIsNull() {
        when(productRepository.searchProducts("")).thenReturn(List.of());

        assertThat(productService.searchProducts(null)).isEmpty();
        verify(productRepository).searchProducts("");
    }

    @Test
    void updatesAllowedFieldsWithoutChangingActiveStatus() {
        Product existingProduct = productMapper.toEntity(
                request("Notebook", new BigDecimal("999.99"))
        );
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(existingProduct));
        when(productRepository.saveAndFlush(existingProduct)).thenReturn(existingProduct);

        Optional<ProductResponse> response = productService.updateProduct(
                1L,
                request("Notebook Pro", new BigDecimal("1299.99"))
        );

        assertThat(response).isPresent();
        assertThat(response.orElseThrow().name()).isEqualTo("Notebook Pro");
        assertThat(response.orElseThrow().price()).isEqualByComparingTo("1299.99");
        assertThat(response.orElseThrow().active()).isTrue();
    }

    @Test
    void returnsEmptyWhenProductToUpdateDoesNotExist() {
        when(productRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        Optional<ProductResponse> response = productService.updateProduct(
                99L,
                request("Notebook Pro", new BigDecimal("1299.99"))
        );

        assertThat(response).isEmpty();
        verify(productRepository, never()).saveAndFlush(any(Product.class));
    }

    @Test
    void deactivatesExistingProductInsteadOfDeletingIt() {
        Product product = productMapper.toEntity(request("Notebook", new BigDecimal("999.99")));
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));
        when(productRepository.saveAndFlush(product)).thenReturn(product);

        boolean deleted = productService.deleteProduct(1L);

        assertThat(deleted).isTrue();
        assertThat(product.getActive()).isFalse();
        verify(productRepository).saveAndFlush(product);
        verify(productRepository, never()).delete(any(Product.class));
    }

    @Test
    void doesNotDeactivateMissingProduct() {
        when(productRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThat(productService.deleteProduct(99L)).isFalse();
        verify(productRepository, never()).saveAndFlush(any(Product.class));
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
