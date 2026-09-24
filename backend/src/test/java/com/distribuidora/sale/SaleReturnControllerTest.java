package com.distribuidora.sale;

import com.distribuidora.sale.api.SaleReturnController;
import com.distribuidora.sale.api.SaleReturnDtos;
import com.distribuidora.sale.application.SaleReturnService;
import com.distribuidora.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SaleReturnControllerTest {
    private final SaleReturnService service = mock(SaleReturnService.class);
    private final SaleReturnController controller = new SaleReturnController(service);
    private final MockMvc mockMvc = standaloneSetup(controller)
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void createsReturnAndReturnsCreatedResponse() {
        UUID saleId = UUID.randomUUID();
        var request = new SaleReturnDtos.ReturnRequest("Producto dañado", List.of());
        var response = new SaleReturnDtos.ReturnResponse(UUID.randomUUID(), saleId, "Producto dañado", List.of());
        when(service.create(saleId, request)).thenReturn(new SaleReturnService.ReturnResult(
            response.returnId(), response.saleId(), response.reason(), List.of()));

        assertThat(controller.create(saleId, request)).isEqualTo(response);
        verify(service).create(saleId, request);
    }

    @Test
    void requiresAdministratorAuthority() throws Exception {
        Method method = SaleReturnController.class.getDeclaredMethod("create", UUID.class, SaleReturnDtos.ReturnRequest.class);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");
        assertThat(method.getAnnotation(ResponseStatus.class).value().value()).isEqualTo(201);
    }

    @Test
    void httpCreateBindsReturnItemsAndSerializesCreatedReturn() throws Exception {
        UUID saleId = UUID.randomUUID();
        UUID saleItemId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID returnId = UUID.randomUUID();
        var request = new SaleReturnDtos.ReturnRequest("Producto dañado", List.of(
            new SaleReturnDtos.ReturnItemRequest(saleItemId, new java.math.BigDecimal("1.0"))));
        when(service.create(saleId, request)).thenReturn(new SaleReturnService.ReturnResult(returnId, saleId,
            "Producto dañado", List.of(new SaleReturnService.ReturnItemResult(saleItemId, productId,
                new java.math.BigDecimal("1.0")))));

        mockMvc.perform(post("/api/sales/{saleId}/returns", saleId)
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                      "reason": "Producto dañado",
                      "items": [{"saleItemId": "%s", "quantity": 1.0}]
                    }
                    """.formatted(saleItemId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.returnId").value(returnId.toString()))
            .andExpect(jsonPath("$.saleId").value(saleId.toString()))
            .andExpect(jsonPath("$.items[0].productId").value(productId.toString()));

        verify(service).create(saleId, request);
    }

    @Test
    void httpCreateRejectsReturnWithoutItemsBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/sales/{saleId}/returns", UUID.randomUUID())
                .contentType(APPLICATION_JSON)
                .content("""
                    {"reason":"Producto dañado","items":[]}
                    """))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
