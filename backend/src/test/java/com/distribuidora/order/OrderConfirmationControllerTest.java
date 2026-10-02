package com.distribuidora.order;

import com.distribuidora.order.api.OrderConfirmationController;
import com.distribuidora.order.api.OrderExceptionHandler;
import com.distribuidora.order.application.IdempotencyConflictException;
import com.distribuidora.order.application.OrderConfirmationService;
import com.distribuidora.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.UUID;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class OrderConfirmationControllerTest {
    private final OrderConfirmationService service = mock(OrderConfirmationService.class);
    private final MockMvc mockMvc = standaloneSetup(new OrderConfirmationController(service))
        .setControllerAdvice(new OrderExceptionHandler(), new ApiExceptionHandler())
        .build();

    @Test
    void bindsSelectedOrderDate() throws Exception {
        when(service.confirm(any())).thenReturn(new OrderConfirmationService.ConfirmationResult(
            UUID.randomUUID(), UUID.randomUUID(), "ORD-DATE", "SAL-DATE", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, null));
        mockMvc.perform(post("/api/orders/confirm").contentType(APPLICATION_JSON).content(datedRequest("2026-09-29")))
            .andExpect(status().isCreated());
        var command = org.mockito.ArgumentCaptor.forClass(OrderConfirmationService.ConfirmationCommand.class);
        verify(service).confirm(command.capture());
        assertThat(command.getValue().orderDate()).isEqualTo(LocalDate.of(2026, 9, 29));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-09-31", "2026-02-29", "29/09/2026"})
    void rejectsInvalidOrderDateBeforeConfirmation(String date) throws Exception {
        mockMvc.perform(post("/api/orders/confirm").contentType(APPLICATION_JSON).content(datedRequest(date)))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private String datedRequest(String date) {
        return """
            {"idempotencyKey":"dated-key","customerId":"11111111-1111-4111-8111-111111111111",
             "orderDate":"%s","lines":[{"productId":"22222222-2222-4222-8222-222222222222","quantity":1,"lineDiscountPercent":0}],
             "orderDiscountPercent":0,"payments":[]}
            """.formatted(date);
    }

    @Test
    void mapsIdempotencyConflictToSpecificHttpProblem() throws Exception {
        doThrow(new IdempotencyConflictException()).when(service).confirm(any());

        mockMvc.perform(post("/api/orders/confirm")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                      "idempotencyKey": "same-key",
                      "customerId": "11111111-1111-4111-8111-111111111111",
                      "lines": [{
                        "productId": "22222222-2222-4222-8222-222222222222",
                        "quantity": 1,
                        "lineDiscountPercent": 0
                      }],
                      "orderDiscountPercent": 0,
                      "payments": []
                    }
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
            .andExpect(jsonPath("$.title").value("Conflict"));

        verify(service).confirm(any());
    }
}
