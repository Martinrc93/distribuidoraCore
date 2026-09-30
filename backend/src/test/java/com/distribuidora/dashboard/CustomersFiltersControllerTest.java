package com.distribuidora.dashboard;

import com.distribuidora.dashboard.api.ReadQueryController;
import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CustomersFiltersControllerTest {
    private final ReadQueryService service = mock(ReadQueryService.class);
    private final MockMvc mvc = standaloneSetup(new ReadQueryController(service))
        .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void bindsCombinedFiltersAndKeepsUnfilteredDefaults() throws Exception {
        UUID sellerId = UUID.randomUUID();
        mvc.perform(get("/api/customers").param("page", "1").param("size", "10").param("search", "North")
            .param("sellerId", sellerId.toString()).param("hasBalance", "true").param("status", "ACTIVE"))
            .andExpect(status().isOk());
        verify(service).customers(1, 10, "North", sellerId, true, "ACTIVE");
        mvc.perform(get("/api/customers")).andExpect(status().isOk());
        verify(service).customers(0, 20, "", null, false, "");
    }

    @Test
    void returnsSellerOptions() throws Exception {
        when(service.customerFilterOptions()).thenReturn(Map.of("sellers", List.of()));
        mvc.perform(get("/api/customers/filter-options")).andExpect(status().isOk());
        verify(service).customerFilterOptions();
    }

    @Test
    void rejectsMalformedFilters() throws Exception {
        mvc.perform(get("/api/customers").param("sellerId", "Ana")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/customers").param("hasBalance", "invalid")).andExpect(status().isBadRequest());
        when(service.customers(0, 20, "", null, false, "unknown")).thenThrow(new IllegalArgumentException("Estado inválido"));
        mvc.perform(get("/api/customers").param("status", "unknown")).andExpect(status().isBadRequest());
    }
}
