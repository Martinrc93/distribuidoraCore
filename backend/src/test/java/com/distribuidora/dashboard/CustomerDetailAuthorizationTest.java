package com.distribuidora.dashboard;

import com.distribuidora.dashboard.api.CustomerDetailController;
import com.distribuidora.dashboard.application.CustomerReadService;
import com.distribuidora.shared.web.PageResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(CustomerDetailAuthorizationTest.Config.class)
class CustomerDetailAuthorizationTest {
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean CustomerReadService queries() { return mock(CustomerReadService.class); }
        @Bean CustomerDetailController controller(CustomerReadService queries) { return new CustomerDetailController(queries); }
    }

    @Autowired CustomerDetailController controller;
    @Autowired CustomerReadService queries;

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        reset(queries);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELLER", "ORDER_CREATE"})
    void deniesCustomerDetailsAndHistoryBeforeReadingData(String authority) {
        authenticate(authority);
        UUID customerId = UUID.randomUUID();
        assertThatThrownBy(() -> controller.customer(customerId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.orders(customerId, 0, 20)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(queries);
    }

    @Test void allowsAdministratorToReadDetailsAndHistory() {
        authenticate("ADMIN_ALL");
        UUID customerId = UUID.randomUUID();
        when(queries.customer(customerId)).thenReturn(Map.of("name", "Customer"));
        var orders = PageResponse.<Map<String, Object>>of(List.of(Map.of("number", "PED-001")), 0, 20, 1);
        when(queries.orders(customerId, 0, 20)).thenReturn(orders);
        assertThat(controller.customer(customerId)).containsEntry("name", "Customer");
        assertThat(controller.orders(customerId, 0, 20)).isEqualTo(orders);
    }

    private void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "test", "", List.of(new SimpleGrantedAuthority(authority))));
    }
}
