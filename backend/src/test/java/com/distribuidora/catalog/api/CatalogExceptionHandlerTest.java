package com.distribuidora.catalog.api;

import com.distribuidora.catalog.application.ProductPriceValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogExceptionHandlerTest {

    @Test
    void mapsInvalidProductPricesToBadRequestWithAffectedPriceLists() {
        UUID priceListId = UUID.randomUUID();
        ProductPriceValidationException exception = new ProductPriceValidationException(
            "El nuevo costo supera listas activas",
            List.of(new ProductPriceValidationException.AffectedPriceList(
                priceListId,
                "GENERAL",
                BigDecimal.valueOf(100)
            ))
        );
        HttpServletRequest request = new MockHttpServletRequest("PUT", "/api/products/123");

        ResponseEntity<ProblemDetail> response = new CatalogExceptionHandler()
            .handleProductPriceValidation(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTitle()).isEqualTo("Invalid product prices");
        assertThat(response.getBody().getProperties())
            .containsEntry("code", "INVALID_PRODUCT_PRICES")
            .containsEntry("instance", "/api/products/123")
            .containsEntry("affectedPriceLists", exception.getAffectedPriceLists());
    }
}
