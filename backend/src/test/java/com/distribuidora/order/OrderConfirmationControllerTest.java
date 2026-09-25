package com.distribuidora.order;

import com.distribuidora.order.api.OrderConfirmationController;
import com.distribuidora.order.api.OrderExceptionHandler;
import com.distribuidora.order.application.IdempotencyConflictException;
import com.distribuidora.order.application.OrderConfirmationService;
import com.distribuidora.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
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
