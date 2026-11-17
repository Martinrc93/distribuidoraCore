package com.distribuidora.pricing;

import com.distribuidora.pricing.api.PricingQueryController;
import com.distribuidora.pricing.application.PricingQueryService;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PricingBatchControllerTest {
    @org.junit.jupiter.api.BeforeEach
    void authenticate() { org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
        new org.springframework.security.authentication.TestingAuthenticationToken(UUID.randomUUID().toString(), "test", "ORDER_CREATE")); }
    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @Test
    void checksCustomerScopeBeforeResolvingPrices() {
        var queries = mock(PricingQueryService.class);
        var access = mock(CurrentUserAccess.class);
        var controller = new PricingQueryController(queries, access);
        UUID customer = UUID.randomUUID();
        var products = List.of(UUID.randomUUID());
        controller.resolveBatch(customer, products, null, null);
        var order = inOrder(access, queries);
        order.verify(access).requireCustomerAccess(customer);
        order.verify(queries).resolveBatch(customer, products, null);
    }

    @Test
    void doesNotResolveAnInaccessibleCustomer() {
        var queries = mock(PricingQueryService.class);
        var access = mock(CurrentUserAccess.class);
        var controller = new PricingQueryController(queries, access);
        UUID customer = UUID.randomUUID();
        doThrow(new EmptyResultDataAccessException(1)).when(access).requireCustomerAccess(customer);
        assertThatThrownBy(() -> controller.resolveBatch(customer, List.of(UUID.randomUUID()), null, null))
            .isInstanceOf(EmptyResultDataAccessException.class);
        verifyNoInteractions(queries);
    }
}
