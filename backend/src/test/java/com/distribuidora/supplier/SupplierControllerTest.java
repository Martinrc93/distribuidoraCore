package com.distribuidora.supplier;

import com.distribuidora.shared.error.ApiExceptionHandler;
import com.distribuidora.shared.web.PageResponse;
import com.distribuidora.supplier.api.SupplierController;
import com.distribuidora.supplier.application.SupplierService;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@SpringJUnitConfig(SupplierControllerTest.Config.class)
class SupplierControllerTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean SupplierService service() { return mock(SupplierService.class); }
        @Bean SupplierController controller(SupplierService service) { return new SupplierController(service); }
    }
    @Autowired SupplierController controller;
    @Autowired SupplierService service;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        authenticate("ADMIN_ALL");
        mvc = standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); reset(service); }

    @Test void acceptsNameOnlyAndReturnsCreatedId() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any())).thenReturn(id);
        mvc.perform(post("/api/suppliers").contentType(APPLICATION_JSON).content("{\"name\":\"Supplier\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id.toString()));
        verify(service).create(new SupplierService.SupplierInput("Supplier", null, null, null));
    }
    @Test void rejectsBlankNameAndInvalidEmail() throws Exception {
        for (String body : List.of("{\"name\":\" \"}", "{\"name\":\"Supplier\",\"email\":\"invalid\"}", "{}")) {
            mvc.perform(post("/api/suppliers").contentType(APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
    @Test void supportsPaginatedReadAndContactUpdate() throws Exception {
        when(service.list(1, 20, "North")).thenReturn(PageResponse.of(List.of(), 1, 20, 0));
        mvc.perform(get("/api/suppliers?page=1&size=20&search=North")).andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1));
        UUID id = UUID.randomUUID();
        mvc.perform(put("/api/suppliers/" + id).contentType(APPLICATION_JSON).content("{\"name\":\"Supplier\",\"phone\":\"123\"}"))
            .andExpect(status().isNoContent());
        verify(service).update(id, new SupplierService.SupplierInput("Supplier", "123", null, null));
    }
    @Test void deniesSellerReadsAndWritesBeforeAccessingService() throws Exception {
        authenticate("ORDER_CREATE");
        mvc.perform(get("/api/suppliers")).andExpect(status().isForbidden());
        mvc.perform(post("/api/suppliers").contentType(APPLICATION_JSON).content("{\"name\":\"Supplier\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/suppliers/" + UUID.randomUUID()).contentType(APPLICATION_JSON).content("{\"name\":\"Supplier\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    private void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            UUID.randomUUID().toString(), "", List.of(new SimpleGrantedAuthority(authority))));
    }
}
