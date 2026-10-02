package com.distribuidora.dashboard;

import com.distribuidora.dashboard.api.DashboardAnalyticsController;
import com.distribuidora.dashboard.application.DashboardAnalyticsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(DashboardAnalyticsAuthorizationTest.Config.class)
class DashboardAnalyticsAuthorizationTest {
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean DashboardAnalyticsService analytics() { return mock(DashboardAnalyticsService.class); }
        @Bean DashboardAnalyticsController controller(DashboardAnalyticsService service) { return new DashboardAnalyticsController(service); }
    }
    @Autowired DashboardAnalyticsController controller;
    @Autowired DashboardAnalyticsService service;
    @AfterEach void clear() { SecurityContextHolder.clearContext(); reset(service); }

    @Test void rejectsSellerBeforeReadingFinancialData() {
        authenticate("SELLER");
        assertThatThrownBy(() -> controller.report(null, null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(service);
    }
    @Test void allowsAdministrator() {
        authenticate("ADMIN_ALL");
        when(service.report(null, null)).thenReturn(Map.of("sales", 42));
        assertThat(controller.report(null, null)).containsEntry("sales", 42);
    }
    private void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test", "", List.of(new SimpleGrantedAuthority(authority))));
    }
}
