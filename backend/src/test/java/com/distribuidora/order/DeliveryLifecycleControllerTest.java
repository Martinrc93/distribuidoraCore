package com.distribuidora.order;

import com.distribuidora.order.api.DeliveryLifecycleController;
import com.distribuidora.order.api.DeliveryLifecycleDtos;
import com.distribuidora.order.application.DeliveryLifecycleService;
import com.distribuidora.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.List;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class DeliveryLifecycleControllerTest {
    private final DeliveryLifecycleService service = mock(DeliveryLifecycleService.class);
    private final DeliveryLifecycleController controller = new DeliveryLifecycleController(service);
    private final MockMvc mockMvc = standaloneSetup(controller)
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void delegatesDeliveryAttemptAndCancellation() {
        UUID orderId = UUID.randomUUID();
        var attempt = new DeliveryLifecycleDtos.DeliveryAttemptRequest("FAILED", "Dirección cerrada");

        assertThat(controller.recordAttempt(orderId, attempt).getStatusCode().value()).isEqualTo(204);
        assertThat(controller.cancel(orderId).getStatusCode().value()).isEqualTo(204);
        verify(service).recordAttempt(orderId, attempt);
        verify(service).cancel(orderId);
    }

    @Test
    void delegatesCollectionDetailsAlongWithDeliveryAttempt() {
        UUID orderId = UUID.randomUUID();
        var attempt = new DeliveryLifecycleDtos.DeliveryAttemptRequest("DELIVERED", null, List.of(
            new DeliveryLifecycleDtos.DeliveryPaymentRequest("BANK_TRANSFER", new BigDecimal("12.50"))), "TR-123");

        assertThat(controller.recordAttempt(orderId, attempt).getStatusCode().value()).isEqualTo(204);
        verify(service).recordAttempt(orderId, attempt);
    }

    @Test
    void protectsEndpointsWithSellerOrAdminAndAdminOnlyAuthorities() throws Exception {
        Method attempt = DeliveryLifecycleController.class.getDeclaredMethod("recordAttempt", UUID.class,
            DeliveryLifecycleDtos.DeliveryAttemptRequest.class);
        Method cancel = DeliveryLifecycleController.class.getDeclaredMethod("cancel", UUID.class);

        assertThat(attempt.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAnyAuthority('SALE_DELIVER', 'ADMIN_ALL')");
        assertThat(cancel.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAuthority('ADMIN_ALL')");
    }

    @Test
    void httpRecordAttemptBindsNestedCollectionDetails() throws Exception {
        UUID orderId = UUID.randomUUID();

        mockMvc.perform(post("/api/orders/{id}/delivery-attempts", orderId)
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                      "result": "DELIVERED",
                      "observation": null,
                      "payments": [{"method": "BANK_TRANSFER", "amount": 12.50}],
                      "transferReference": "TR-123"
                    }
                    """))
            .andExpect(status().isNoContent());

        verify(service).recordAttempt(orderId, new DeliveryLifecycleDtos.DeliveryAttemptRequest(
            "DELIVERED", null,
            List.of(new DeliveryLifecycleDtos.DeliveryPaymentRequest("BANK_TRANSFER", new BigDecimal("12.50"))),
            "TR-123"));
    }

    @Test
    void httpRecordAttemptRejectsInvalidNestedPaymentBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/orders/{id}/delivery-attempts", UUID.randomUUID())
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                      "result": "DELIVERED",
                      "payments": [{"method": "CHECK", "amount": 12.50}]
                    }
                    """))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
