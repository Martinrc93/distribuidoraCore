package com.distribuidora.dashboard;

import com.distribuidora.dashboard.api.ReadQueryController;
import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import com.distribuidora.shared.web.PageResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class DashboardSummaryControllerTest {
    private final ReadQueryService service = mock(ReadQueryService.class);
    private final MockMvc mvc = standaloneSetup(new ReadQueryController(service))
        .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void bindsDateRangeAndAllowsDefaults() throws Exception {
        LocalDate day = LocalDate.of(2026, 9, 30);
        when(service.dashboard(day, day)).thenReturn(Map.of());
        when(service.dashboard(null, null)).thenReturn(Map.of());
        mvc.perform(get("/api/dashboard").param("dateMin", "2026-09-30").param("dateMax", "2026-09-30")).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard")).andExpect(status().isOk());
        verify(service).dashboard(day, day);
        verify(service).dashboard(null, null);
    }

    @Test
    void rejectsInvalidAndReversedDates() throws Exception {
        mvc.perform(get("/api/dashboard").param("dateMin", "2026-09-31")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        LocalDate day = LocalDate.of(2026, 9, 30);
        when(service.dashboard(day.plusDays(1), day)).thenThrow(new IllegalArgumentException("Rango inválido"));
        mvc.perform(get("/api/dashboard").param("dateMin", "2026-10-01").param("dateMax", "2026-09-30")).andExpect(status().isBadRequest());
    }

    @Test
    void preservesAdminOnlyAccess() throws Exception {
        assertThat(ReadQueryController.class.getDeclaredMethod("dashboard", LocalDate.class, LocalDate.class)
            .getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");
    }

    @Test
    void bindsSellerOrderFiltersAndPagination() throws Exception {
        UUID seller = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 9, 30);
        when(service.dashboardSellerOrders(seller, false, day, day, 1, 20)).thenReturn(PageResponse.of(List.of(), 1, 20, 0));
        mvc.perform(get("/api/dashboard/seller-orders").param("sellerId", seller.toString()).param("page", "1")
            .param("dateMin", "2026-09-30").param("dateMax", "2026-09-30")).andExpect(status().isOk());
        verify(service).dashboardSellerOrders(seller, false, day, day, 1, 20);
    }

    @Test
    void deniesSellerRequestsBeforeQueryingAndAllowsAdminRequests() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(MethodSecurity.class);
            context.registerBean(ReadQueryService.class, () -> service);
            context.registerBean(ReadQueryController.class, () -> new ReadQueryController(service));
            context.refresh();
            var controller = context.getBean(ReadQueryController.class);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "seller", "unused", List.of(new SimpleGrantedAuthority("SELLER"))));
            assertThatThrownBy(() -> controller.dashboard(null, null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> controller.dashboardSellerOrders(null, true, null, null, 0, 20)).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(service);
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin", "unused", List.of(new SimpleGrantedAuthority("ADMIN_ALL"))));
            controller.dashboard(null, null);
            controller.dashboardSellerOrders(null, true, null, null, 0, 20);
            verify(service).dashboard(null, null);
            verify(service).dashboardSellerOrders(null, true, null, null, 0, 20);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurity {}
}
