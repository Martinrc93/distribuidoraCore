package com.distribuidora.expense;

import com.distribuidora.expense.api.ExpenseController;
import com.distribuidora.expense.application.ExpenseService;
import com.distribuidora.expense.application.ExpenseService.Entry;
import com.distribuidora.shared.error.ApiExceptionHandler;
import com.distribuidora.shared.web.PageResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@SpringJUnitConfig(ExpenseControllerTest.Config.class)
class ExpenseControllerTest {
    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        ExpenseService service() {
            return mock(ExpenseService.class);
        }

        @Bean
        ExpenseController controller(ExpenseService service) {
            return new ExpenseController(service);
        }
    }

    @Autowired
    ExpenseController controller;

    @Autowired
    ExpenseService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        authenticate("ADMIN_ALL");
        mvc = standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        reset(service);
    }

    @Test
    void listsExpensesWithFiltersForAdmin() throws Exception {
        UUID id = UUID.randomUUID();
        var entry = new Entry(id, new BigDecimal("1500.50"), "FUEL", "Nafta", Instant.parse("2026-10-01T12:00:00Z"));
        when(service.list(1, 10, "FUEL", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
            .thenReturn(PageResponse.of(List.of(entry), 1, 10, 1));

        mvc.perform(get("/api/expenses?page=1&size=10&type=FUEL&dateMin=2026-10-01&dateMax=2026-10-31"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].id").value(id.toString()))
            .andExpect(jsonPath("$.content[0].type").value("FUEL"));

        verify(service).list(1, 10, "FUEL", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
    }

    @Test
    void rejectsInvalidExpenseTypeQueryBeforeCallingService() throws Exception {
        mvc.perform(get("/api/expenses?type=UNKNOWN")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void createsExpenseAndReturnsCreatedEntry() throws Exception {
        UUID key = UUID.randomUUID();
        var created = new Entry(key, new BigDecimal("250.00"), "SERVICES", "Internet", Instant.parse("2026-10-02T10:00:00Z"));
        when(service.create(key, new BigDecimal("250.00"), "SERVICES", "Internet")).thenReturn(created);

        mvc.perform(post("/api/expenses")
                .contentType(APPLICATION_JSON)
                .content("""
                    {"idempotencyKey":"%s","amount":250.00,"type":"SERVICES","observation":"Internet"}
                    """.formatted(key)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(key.toString()))
            .andExpect(jsonPath("$.type").value("SERVICES"));

        verify(service).create(key, new BigDecimal("250.00"), "SERVICES", "Internet");
    }

    @Test
    void rejectsInvalidExpensePayloadsBeforeCallingService() throws Exception {
        String key = UUID.randomUUID().toString();
        for (String payload : List.of(
            "{\"idempotencyKey\":\"%s\",\"amount\":0,\"type\":\"FUEL\"}".formatted(key),
            "{\"idempotencyKey\":\"%s\",\"amount\":10.123,\"type\":\"FUEL\"}".formatted(key),
            "{\"idempotencyKey\":\"%s\",\"amount\":10,\"type\":\"INVALID\"}".formatted(key),
            "{\"amount\":10,\"type\":\"FUEL\"}"
        )) {
            mvc.perform(post("/api/expenses").contentType(APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void deniesNonAdminAccessToListAndCreate() throws Exception {
        authenticate("ORDER_CREATE");
        mvc.perform(get("/api/expenses")).andExpect(status().isForbidden());
        mvc.perform(post("/api/expenses")
                .contentType(APPLICATION_JSON)
                .content("{\"idempotencyKey\":\"%s\",\"amount\":10,\"type\":\"OTHER\"}".formatted(UUID.randomUUID())))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            UUID.randomUUID().toString(), "", List.of(new SimpleGrantedAuthority(authority))));
    }
}
