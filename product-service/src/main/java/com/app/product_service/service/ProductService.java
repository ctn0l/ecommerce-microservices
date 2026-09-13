package com.app.product_service.service;

import com.app.product_service.dto.ProductRequest;
import com.app.product_service.dto.ProductResponse;
import com.app.product_service.mapper.ProductMapper;
import com.app.product_service.model.Product;
import com.app.product_service.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    public List<ProductResponse> fetchAllProducts() {
        return productRepository.findAllByActiveTrue().stream()
                .map(productMapper::toResponse)
                .toList();
    }

    public Optional<ProductResponse> fetchProduct(Long id) {
        return productRepository.findByIdAndActiveTrue(id)
                .map(productMapper::toResponse);
    }

    public List<ProductResponse> searchProducts(String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.strip();
        return productRepository.searchProducts(normalizedKeyword).stream()
                .map(productMapper::toResponse)
                .toList();
    }

    @Transactional
    public ProductResponse createProduct(ProductRequest productRequest) {
        Product product = productMapper.toEntity(productRequest);
        Product savedProduct = productRepository.saveAndFlush(product);
        return productMapper.toResponse(savedProduct);
    }

    @Transactional
    public Optional<ProductResponse> updateProduct(Long id, ProductRequest productRequest) {
        return productRepository.findByIdForUpdate(id)
                .filter(product -> Boolean.TRUE.equals(product.getActive()))
                .map(existingProduct -> {
                    if (!existingProduct.getStockQuantity().equals(productRequest.stockQuantity())
                            && productRepository.hasActiveReservations(id)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Cannot replace stock quantity while reservations are pending");
                    }
                    productMapper.updateEntity(productRequest, existingProduct);
                    Product savedProduct = productRepository.saveAndFlush(existingProduct);
                    return productMapper.toResponse(savedProduct);
                });
    }

    @Transactional
    public boolean deleteProduct(Long id) {
        return productRepository.findByIdForUpdate(id)
                .filter(product -> Boolean.TRUE.equals(product.getActive()))
                .map(product -> {
                    product.setActive(false);
                    productRepository.saveAndFlush(product);
                    return true;
                })
                .orElse(false);
    }
}
