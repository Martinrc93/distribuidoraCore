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
    @Test
    void checksCustomerScopeBeforeResolvingPrices() {
        var queries = mock(PricingQueryService.class);
        var access = mock(CurrentUserAccess.class);
        var controller = new PricingQueryController(queries, access);
        UUID customer = UUID.randomUUID();
        var products = List.of(UUID.randomUUID());
        controller.resolveBatch(customer, products, null);
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
        assertThatThrownBy(() -> controller.resolveBatch(customer, List.of(UUID.randomUUID()), null))
            .isInstanceOf(EmptyResultDataAccessException.class);
        verifyNoInteractions(queries);
    }
}
