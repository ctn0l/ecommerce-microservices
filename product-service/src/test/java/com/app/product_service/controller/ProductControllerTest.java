package com.app.product_service.controller;

import com.app.product_service.dto.ProductResponse;
import com.app.product_service.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    private static final String VALID_REQUEST = """
            {
              "name": "Notebook",
              "description": "Notebook per uso professionale",
              "price": 999.99,
              "stockQuantity": 10,
              "category": "Elettronica",
              "imageUrl": "https://example.com/notebook.jpg"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Test
    void returnsAllActiveProducts() throws Exception {
        when(productService.fetchAllProducts()).thenReturn(List.of(response()));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Notebook"))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    void returnsProductWhenItExists() throws Exception {
        when(productService.fetchProduct(1L)).thenReturn(Optional.of(response()));

        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void returnsNotFoundForMissingProduct() throws Exception {
        when(productService.fetchProduct(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/products/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void searchesProducts() throws Exception {
        when(productService.searchProducts("note")).thenReturn(List.of(response()));

        mockMvc.perform(get("/api/products/search").param("keyword", "note"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Notebook"));

        verify(productService).searchProducts("note");
    }

    @Test
    void createsProductAndReturnsLocationHeader() throws Exception {
        when(productService.createProduct(any())).thenReturn(response());

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/products/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.price").value(999.99));
    }

    @Test
    void rejectsInvalidProductRequest() throws Exception {
        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "",
                                  "price": -1,
                                  "stockQuantity": -1,
                                  "category": ""
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatesExistingProduct() throws Exception {
        when(productService.updateProduct(any(), any())).thenReturn(Optional.of(response()));

        mockMvc.perform(put("/api/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void returnsNotFoundWhenUpdatingMissingProduct() throws Exception {
        when(productService.updateProduct(any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(put("/api/products/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST))
                .andExpect(status().isNotFound());
    }

    @Test
    void deactivatesExistingProduct() throws Exception {
        when(productService.deleteProduct(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/products/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void returnsNotFoundWhenDeletingMissingProduct() throws Exception {
        when(productService.deleteProduct(99L)).thenReturn(false);

        mockMvc.perform(delete("/api/products/99"))
                .andExpect(status().isNotFound());
    }

    private ProductResponse response() {
        return new ProductResponse(
                1L,
                "Notebook",
                "Notebook per uso professionale",
                new BigDecimal("999.99"),
                10,
                "Elettronica",
                "https://example.com/notebook.jpg",
                true
        );
    }
}
