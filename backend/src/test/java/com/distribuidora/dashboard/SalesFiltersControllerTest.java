package com.distribuidora.dashboard;

import com.distribuidora.dashboard.api.ReadQueryController;
import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.shared.web.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDate;
import com.distribuidora.shared.error.ApiExceptionHandler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SalesFiltersControllerTest {
    private final ReadQueryService service = mock(ReadQueryService.class);
    private final MockMvc mvc = standaloneSetup(new ReadQueryController(service)).setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void bindsIndependentFiltersAndPagination() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        LocalDate min = LocalDate.of(2026, 9, 1);
        LocalDate max = LocalDate.of(2026, 9, 30);
        when(service.sales(1, 10, "SAL", customerId, sellerId, true, min, max)).thenReturn(PageResponse.of(List.of(), 1, 10, 0));
        mvc.perform(get("/api/sales").param("page", "1").param("size", "10")
            .param("search", "SAL").param("customerId", customerId.toString()).param("sellerId", sellerId.toString())
            .param("dateMin", "2026-09-01").param("dateMax", "2026-09-30")
            .param("pendingBalance", "true")).andExpect(status().isOk());
        verify(service).sales(1, 10, "SAL", customerId, sellerId, true, min, max);
    }

    @Test
    void defaultsToAllSalesWithoutFilters() throws Exception {
        when(service.sales(0, 20, "", null, null, false, null, null)).thenReturn(PageResponse.of(List.of(), 0, 20, 0));
        mvc.perform(get("/api/sales")).andExpect(status().isOk());
        verify(service).sales(0, 20, "", null, null, false, null, null);
    }

    @Test
    void returnsDropdownOptionsWithoutInterpretingThePathAsASaleId() throws Exception {
        when(service.saleFilterOptions()).thenReturn(Map.of("customers", List.of(), "sellers", List.of()));
        mvc.perform(get("/api/sales/filter-options")).andExpect(status().isOk());
        verify(service).saleFilterOptions();
    }

    @Test
    void rejectsMalformedIdsAndDatesAndReversedRanges() throws Exception {
        mvc.perform(get("/api/sales").param("customerId", "Norte")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/sales").param("dateMin", "2026-09-31")).andExpect(status().isBadRequest());
        LocalDate min = LocalDate.of(2026, 10, 1);
        LocalDate max = LocalDate.of(2026, 9, 30);
        when(service.sales(0, 20, "", null, null, false, min, max)).thenThrow(new IllegalArgumentException("Rango inválido"));
        mvc.perform(get("/api/sales").param("dateMin", "2026-10-01").param("dateMax", "2026-09-30"))
            .andExpect(status().isBadRequest());
    }
}
