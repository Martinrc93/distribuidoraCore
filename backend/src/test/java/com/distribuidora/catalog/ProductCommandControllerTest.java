package com.distribuidora.catalog;

import com.distribuidora.catalog.api.ProductCommandController;
import com.distribuidora.catalog.application.ProductCommandService;
import com.distribuidora.catalog.application.ProductPriceValidationException;
import com.distribuidora.catalog.application.ProductPriceValidationException.AffectedPriceList;
import com.distribuidora.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ProductCommandControllerTest {

    private final ProductCommandService service = mock(ProductCommandService.class);
    private final ProductCommandController controller = new ProductCommandController(service);
    private final MockMvc mockMvc = standaloneSetup(controller)
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void returnsCreatedIdForProductCreation() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any())).thenReturn(id);

        mockMvc.perform(post("/api/products")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                        "sku": "SKU-TEST",
                        "name": "Producto Test",
                        "category": "Bebidas",
                        "presentation": "Unidad",
                        "cost": 100.00
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(id.toString()));

        verify(service).create(any());
    }

    @Test
    void returnsNoContentForProductUpdate() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/api/products/{id}", id)
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                        "sku": "SKU-TEST",
                        "name": "Producto Actualizado",
                        "category": "Bebidas",
                        "presentation": "Pack x6",
                        "cost": 150.00,
                        "prices": []
                    }
                    """))
            .andExpect(status().isNoContent());

        verify(service).update(eq(id), any());
    }

    @Test
    void returnsBadRequestWithAffectedPriceListsWhenCostValidationFails() throws Exception {
        UUID id = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        AffectedPriceList affected = new AffectedPriceList(listId, "GENERAL", BigDecimal.valueOf(100));

        doThrow(new ProductPriceValidationException("El nuevo costo supera listas activas", List.of(affected)))
            .when(service).update(eq(id), any());

        mockMvc.perform(put("/api/products/{id}", id)
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                        "sku": "SKU-TEST",
                        "name": "Producto Test",
                        "category": "Bebidas",
                        "presentation": "Unidad",
                        "cost": 120.00
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_PRODUCT_PRICES"))
            .andExpect(jsonPath("$.affectedPriceLists[0].code").value("GENERAL"))
            .andExpect(jsonPath("$.affectedPriceLists[0].currentPrice").value(100));
    }

    @Test
    void returnsNoContentForStatusUpdate() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(patch("/api/products/{id}/status", id)
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                        "status": "INACTIVE"
                    }
                    """))
            .andExpect(status().isNoContent());

        verify(service).setStatus(id, "INACTIVE");
    }
}
