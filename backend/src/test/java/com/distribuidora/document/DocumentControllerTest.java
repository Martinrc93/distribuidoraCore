package com.distribuidora.document;

import com.distribuidora.document.application.SaleDocumentConflictException;
import com.distribuidora.document.application.SaleDocumentNotFoundException;
import com.distribuidora.document.application.SaleDocumentService;
import com.distribuidora.document.api.DocumentController;
import com.distribuidora.document.rendering.OpenPdfA4Renderer;
import com.distribuidora.document.rendering.OpenPdfTicketRenderer;
import com.distribuidora.document.rendering.SaleDocumentModel;
import com.distribuidora.identity.infrastructure.UserAccountRepository;
import com.distribuidora.shared.error.ApiExceptionHandler;
import com.distribuidora.shared.security.JwtAuthenticationFilter;
import com.distribuidora.shared.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DocumentController.class)
@Import({DocumentController.class, DocumentControllerTest.TestSecurityConfiguration.class, ApiExceptionHandler.class, JwtAuthenticationFilter.class})
class DocumentControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SaleDocumentService service;

    @MockBean
    private OpenPdfA4Renderer renderer;

    @MockBean
    private OpenPdfTicketRenderer ticketRenderer;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private UserAccountRepository userAccountRepository;

    @TestConfiguration
    @EnableMethodSecurity
    static class TestSecurityConfiguration {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter)
            throws Exception {
            http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

            return http.build();
        }
    }

    @Test
    void sellerCanDownloadPdfWithExactHeadersAndSanitizedFilename() throws Exception {
        UUID orderId = UUID.randomUUID();
        SaleDocumentModel model = model("V-2026/001\r\n");
        byte[] pdf = "%PDF-test".getBytes();
        when(service.load(orderId)).thenReturn(model);
        when(renderer.render(model)).thenReturn(pdf);

        mockMvc.perform(get("/api/orders/{orderId}/documents/a4", orderId)
                .with(user("seller").authorities(() -> "ORDER_CREATE")))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string(HttpHeaders.CONTENT_LENGTH, String.valueOf(pdf.length)))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"venta-V-2026_001__.pdf\""))
            .andExpect(content().bytes(pdf));

        verify(service).load(orderId);
        verify(renderer).render(model);
    }

    @Test
    void adminCanDownloadPdf() throws Exception {
        UUID orderId = UUID.randomUUID();
        SaleDocumentModel model = model("V-2");
        when(service.load(orderId)).thenReturn(model);
        when(renderer.render(model)).thenReturn("%PDF".getBytes());

        mockMvc.perform(get("/api/orders/{orderId}/documents/a4", orderId)
                .with(user("admin").authorities(() -> "ADMIN_ALL")))
            .andExpect(status().isOk());
    }

    @Test
    void sellerCanDownloadTicketPdf() throws Exception {
        UUID orderId = UUID.randomUUID();
        SaleDocumentModel model = model("V-3");
        byte[] pdf = "%PDF-ticket".getBytes();
        when(service.load(orderId)).thenReturn(model);
        when(ticketRenderer.render(model)).thenReturn(pdf);

        mockMvc.perform(get("/api/orders/{orderId}/documents/ticket", orderId)
                .with(user("seller").authorities(() -> "ORDER_CREATE")))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ticket-V-3.pdf\""))
            .andExpect(content().bytes(pdf));
        verify(service).load(orderId);
        verify(ticketRenderer).render(model);
    }

    @Test
    void authenticatedUserWithoutPermissionIsForbidden() throws Exception {
        mockMvc.perform(get("/api/orders/{orderId}/documents/a4", UUID.randomUUID())
                .with(user("viewer").authorities(() -> "CUSTOMER_READ")))
            .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedUserIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/orders/{orderId}/documents/a4", UUID.randomUUID()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void missingOrderIsNotFound() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(service.load(orderId)).thenThrow(new SaleDocumentNotFoundException(orderId));

        mockMvc.perform(get("/api/orders/{orderId}/documents/a4", orderId)
                .with(user("seller").authorities(() -> "ORDER_CREATE")))
            .andExpect(status().isNotFound());
    }

    @Test
    void existingOrderWithoutSaleIsConflict() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(service.load(orderId)).thenThrow(new SaleDocumentConflictException(orderId));

        mockMvc.perform(get("/api/orders/{orderId}/documents/a4", orderId)
                .with(user("seller").authorities(() -> "ORDER_CREATE")))
            .andExpect(status().isConflict());
    }

    @Test
    void rendererFailureIsInternalServerError() throws Exception {
        UUID orderId = UUID.randomUUID();
        SaleDocumentModel model = model("V-3");
        when(service.load(orderId)).thenReturn(model);
        doThrow(new RuntimeException("renderer failed")).when(renderer).render(model);

        mockMvc.perform(get("/api/orders/{orderId}/documents/a4", orderId)
                .with(user("seller").authorities(() -> "ORDER_CREATE")))
            .andExpect(status().isInternalServerError());
    }

    @Test
    void malformedOrderIdUsesBadRequestConvention() throws Exception {
        mockMvc.perform(get("/api/orders/not-a-uuid/documents/a4")
                .with(user("seller").authorities(() -> "ORDER_CREATE")))
            .andExpect(status().isBadRequest());
    }

    private static SaleDocumentModel model(String saleNumber) {
        return new SaleDocumentModel(
            saleNumber,
            LocalDate.of(2026, 9, 19),
            "Cliente",
            "Vendedor",
            List.of(),
            List.of(),
            java.math.BigDecimal.TEN,
            java.math.BigDecimal.ZERO);
    }
}
