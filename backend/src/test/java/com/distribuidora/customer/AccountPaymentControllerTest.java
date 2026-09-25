package com.distribuidora.customer;

import com.distribuidora.customer.api.AccountPaymentController;
import com.distribuidora.customer.api.AccountPaymentDtos;
import com.distribuidora.customer.application.AccountPaymentService;
import com.distribuidora.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
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

class AccountPaymentControllerTest {
    private final AccountPaymentService service = mock(AccountPaymentService.class);
    private final AccountPaymentController controller = new AccountPaymentController(service);
    private final MockMvc mockMvc = standaloneSetup(controller)
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void delegatesPaymentAllocationAndReturnsCreated() {
        UUID customerId = UUID.randomUUID();
        var request = new AccountPaymentDtos.PaymentRequest(new BigDecimal("10.00"), "CASH", null, null);
        var serviceResult = new AccountPaymentService.PaymentResult(customerId, new BigDecimal("10.0000"),
            new BigDecimal("20.0000"), new BigDecimal("10.0000"), "FIFO", List.of());
        var response = new AccountPaymentDtos.PaymentResponse(customerId, new BigDecimal("10.0000"),
            new BigDecimal("20.0000"), new BigDecimal("10.0000"), "FIFO", List.of());
        when(service.apply(customerId, request)).thenReturn(serviceResult);

        var result = controller.apply(customerId, request);

        assertThat(result.getStatusCode().value()).isEqualTo(201);
        assertThat(result.getBody()).isEqualTo(response);
        verify(service).apply(customerId, request);
    }

    @Test
    void requiresPaymentOrAdminAuthority() throws Exception {
        var method = AccountPaymentController.class.getDeclaredMethod("apply", UUID.class,
            AccountPaymentDtos.PaymentRequest.class);

        assertThat(method.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasAnyAuthority('SALE_PAYMENT', 'ADMIN_ALL')");
    }

    @Test
    void httpApplyBindsPaymentAndSerializesAllocationSummary() throws Exception {
        UUID customerId = UUID.randomUUID();
        var request = new AccountPaymentDtos.PaymentRequest(new BigDecimal("10.00"), "CASH", null, null);
        when(service.apply(customerId, request)).thenReturn(new AccountPaymentService.PaymentResult(
            customerId, new BigDecimal("10.0000"), new BigDecimal("20.0000"),
            new BigDecimal("10.0000"), "FIFO", List.of()));

        mockMvc.perform(post("/api/customers/{customerId}/account-payments", customerId)
                .contentType(APPLICATION_JSON)
                .content("""
                    {"amount":10.00,"method":"CASH"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.customerId").value(customerId.toString()))
            .andExpect(jsonPath("$.allocationMode").value("FIFO"))
            .andExpect(jsonPath("$.allocations").isArray());

        verify(service).apply(customerId, request);
    }

    @Test
    void httpApplyRejectsUnsupportedPaymentMethodBeforeCallingService() throws Exception {
        mockMvc.perform(post("/api/customers/{customerId}/account-payments", UUID.randomUUID())
                .contentType(APPLICATION_JSON)
                .content("""
                    {"amount":10.00,"method":"CHECK"}
                    """))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
