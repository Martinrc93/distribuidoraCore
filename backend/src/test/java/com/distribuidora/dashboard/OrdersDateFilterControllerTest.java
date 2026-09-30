package com.distribuidora.dashboard;

import com.distribuidora.dashboard.api.ReadQueryController;
import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.shared.error.ApiExceptionHandler;
import com.distribuidora.shared.web.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class OrdersDateFilterControllerTest {
    private final ReadQueryService service = mock(ReadQueryService.class);
    private final MockMvc mvc = standaloneSetup(new ReadQueryController(service))
        .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void bindsIsoDatesAndPassesSearchStatusAndPage() throws Exception {
        LocalDate min = LocalDate.of(2026, 9, 1);
        LocalDate max = LocalDate.of(2026, 9, 30);
        when(service.orders(1, 10, "Norte", "CONFIRMED", min, max)).thenReturn(PageResponse.of(List.of(), 1, 10, 0));
        mvc.perform(get("/api/orders").param("page", "1").param("size", "10")
            .param("search", "Norte").param("status", "CONFIRMED")
            .param("dateMin", "2026-09-01").param("dateMax", "2026-09-30"))
            .andExpect(status().isOk());
        verify(service).orders(1, 10, "Norte", "CONFIRMED", min, max);
    }

    @Test
    void acceptsEmptyAndMissingDates() throws Exception {
        when(service.orders(0, 20, "", "", null, null)).thenReturn(PageResponse.of(List.of(), 0, 20, 0));
        mvc.perform(get("/api/orders").param("dateMin", "").param("dateMax", "")).andExpect(status().isOk());
        mvc.perform(get("/api/orders")).andExpect(status().isOk());
    }

    @Test
    void rejectsMalformedDates() throws Exception {
        mvc.perform(get("/api/orders").param("dateMin", "2026-09-31")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders").param("dateMax", "30/09/2026")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void returnsBadRequestForReversedRanges() throws Exception {
        LocalDate min = LocalDate.of(2026, 10, 1);
        LocalDate max = LocalDate.of(2026, 9, 30);
        when(service.orders(0, 20, "", "", min, max)).thenThrow(new IllegalArgumentException("Rango inválido"));
        mvc.perform(get("/api/orders").param("dateMin", "2026-10-01").param("dateMax", "2026-09-30"))
            .andExpect(status().isBadRequest());
    }
}
