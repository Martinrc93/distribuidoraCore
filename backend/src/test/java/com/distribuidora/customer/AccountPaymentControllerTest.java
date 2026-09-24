package com.distribuidora.customer;

import com.distribuidora.customer.api.AccountPaymentController;
import com.distribuidora.customer.api.AccountPaymentDtos;
import com.distribuidora.customer.application.AccountPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountPaymentControllerTest {
    private final AccountPaymentService service = mock(AccountPaymentService.class);
    private final AccountPaymentController controller = new AccountPaymentController(service);

    @Test
    void delegatesPaymentAllocationAndReturnsCreated() {
        UUID customerId = UUID.randomUUID();
        var request = new AccountPaymentDtos.PaymentRequest(new BigDecimal("10.00"), "CASH", null, null);
        var response = new AccountPaymentDtos.PaymentResponse(customerId, new BigDecimal("10.0000"),
            new BigDecimal("20.0000"), new BigDecimal("10.0000"), "FIFO", List.of());
        when(service.apply(customerId, request)).thenReturn(response);

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
}
