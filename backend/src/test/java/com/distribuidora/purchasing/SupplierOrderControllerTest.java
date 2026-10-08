package com.distribuidora.purchasing;

import com.distribuidora.purchasing.api.SupplierOrderController;
import com.distribuidora.purchasing.application.SupplierOrderService;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@SpringJUnitConfig(SupplierOrderControllerTest.Config.class)
class SupplierOrderControllerTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean SupplierOrderService service() { return mock(SupplierOrderService.class); }
        @Bean SupplierOrderController controller(SupplierOrderService service) { return new SupplierOrderController(service); }
    }
    @Autowired SupplierOrderController controller;
    @Autowired SupplierOrderService service;
    MockMvc mvc;
    @BeforeEach void setup() {
        authenticate("ADMIN_ALL");
        mvc = standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); reset(service); }
    void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(),"",List.of(new SimpleGrantedAuthority(authority))));
    }
    @Test void acceptsIsoCalendarDatesAndExactSupplierFilters() throws Exception {
        UUID supplier = UUID.randomUUID();
        when(service.list(1,20,supplier,LocalDate.of(2026,9,1),LocalDate.of(2026,9,30))).thenReturn(PageResponse.of(List.of(),1,20,0));
        mvc.perform(get("/api/supplier-orders").param("page","1").param("supplierId",supplier.toString()).param("dateMin","2026-09-01").param("dateMax","2026-09-30"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1));
        verify(service).list(1,20,supplier,LocalDate.of(2026,9,1),LocalDate.of(2026,9,30));
    }
    @Test void createsRequestsAndLoadsLastOrderBySupplier() throws Exception {
        UUID id = UUID.randomUUID(), supplier = UUID.randomUUID();
        when(service.create(any())).thenReturn(new SupplierOrderService.OrderResult(id,"PRV-00000001",BigDecimal.TEN));
        mvc.perform(post("/api/supplier-orders").contentType(APPLICATION_JSON).content("{\"supplierId\":\"" + supplier + "\",\"orderDate\":\"2026-09-01\",\"lines\":[],\"idempotencyKey\":\"test\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.number").value("PRV-00000001"));
        when(service.lastOrder(supplier)).thenReturn(Map.of("available",false));
        mvc.perform(get("/api/supplier-orders/supplier/" + supplier + "/last-order")).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false));
    }
    @Test void rejectsSellerReadsWritesDetailsAndHistoryBeforeAccessingTheService() throws Exception {
        authenticate("ORDER_CREATE");
        mvc.perform(get("/api/supplier-orders")).andExpect(status().isForbidden());
        mvc.perform(post("/api/supplier-orders").contentType(APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        mvc.perform(get("/api/supplier-orders/" + UUID.randomUUID())).andExpect(status().isForbidden());
        mvc.perform(get("/api/supplier-orders/supplier/" + UUID.randomUUID() + "/last-order")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
