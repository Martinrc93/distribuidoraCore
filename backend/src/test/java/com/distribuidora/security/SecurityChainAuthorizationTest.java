package com.distribuidora.security;

import com.distribuidora.catalog.application.BrandService;
import com.distribuidora.config.SecurityConfig;
import com.distribuidora.customer.api.AccountPaymentController;
import com.distribuidora.customer.api.AccountPaymentDtos;
import com.distribuidora.customer.application.AccountPaymentService;
import com.distribuidora.document.api.DocumentController;
import com.distribuidora.document.application.SaleDocumentService;
import com.distribuidora.document.rendering.OpenPdfA4Renderer;
import com.distribuidora.document.rendering.OpenPdfTicketRenderer;
import com.distribuidora.document.rendering.SaleDocumentModel;
import com.distribuidora.identity.api.UserAdminController;
import com.distribuidora.identity.application.RoleAdminService;
import com.distribuidora.identity.application.UserAdminService;
import com.distribuidora.identity.domain.UserAccount;
import com.distribuidora.identity.infrastructure.UserAccountRepository;
import com.distribuidora.identity.security.JwtAuthenticationFilter;
import com.distribuidora.identity.security.JwtService;
import com.distribuidora.inventory.api.InventoryCommandController;
import com.distribuidora.inventory.application.InventoryCommandService;
import com.distribuidora.order.api.DeliveryLifecycleController;
import com.distribuidora.order.application.DeliveryLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
    com.distribuidora.catalog.api.BrandController.class,
    DocumentController.class,
    AccountPaymentController.class,
    DeliveryLifecycleController.class,
    InventoryCommandController.class,
    UserAdminController.class,
    com.distribuidora.identity.api.RoleAdminController.class
})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class})
@TestPropertySource(properties = {
    "app.security.jwt-secret=0123456789abcdef0123456789abcdef",
    "app.security.jwt-issuer=security-chain-test"
})
class SecurityChainAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private BrandService brandService;

    @MockitoBean
    private UserAccountRepository users;

    @MockitoBean
    private SaleDocumentService documentService;

    @MockitoBean
    private OpenPdfA4Renderer a4Renderer;

    @MockitoBean
    private OpenPdfTicketRenderer ticketRenderer;

    @MockitoBean
    private AccountPaymentService accountPaymentService;

    @MockitoBean
    private DeliveryLifecycleService deliveryLifecycleService;

    @MockitoBean
    private InventoryCommandService inventoryCommandService;

    @MockitoBean
    private UserAdminService userAdminService;

    @MockitoBean
    private RoleAdminService roleAdminService;

    @Test
    void anonymousRequestToProtectedRouteReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/brands")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Brahma\",\"code\":\"BRAH\"}"))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(brandService, users);
    }

    @Test
    void authenticatedUserWithoutAdminAuthorityIsForbidden() throws Exception {
        UserAccount user = activeUser();
        String token = jwtService.issue(user, List.of("CATALOG_READ"));

        mockMvc.perform(post("/api/brands")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Brahma\",\"code\":\"BRAH\"}"))
            .andExpect(status().isForbidden());

        verify(users).findById(user.getId());
        verifyNoInteractions(brandService);
    }

    @Test
    void activeAdminWithCurrentSessionCanCreateBrand() throws Exception {
        UUID brandId = UUID.randomUUID();
        UserAccount user = activeUser();
        String token = jwtService.issue(user, List.of("ADMIN_ALL"));
        when(brandService.create(any())).thenReturn(brandId);

        mockMvc.perform(post("/api/brands")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Brahma\",\"code\":\"BRAH\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(brandId.toString()));

        verify(users).findById(user.getId());
        verify(brandService).create(any());
    }

    @Test
    void sellerAuthorityCanReachProtectedDocumentRoute() throws Exception {
        UserAccount user = activeUser();
        String token = jwtService.issue(user, List.of("ORDER_CREATE"));
        UUID orderId = UUID.randomUUID();
        SaleDocumentModel model = new SaleDocumentModel(
            "V-SEC-1", LocalDate.of(2026, 9, 24), "Cliente", "Vendedor",
            List.of(), List.of(), BigDecimal.TEN, BigDecimal.ZERO);
        byte[] pdf = "%PDF-security-test".getBytes();
        when(documentService.load(orderId)).thenReturn(model);
        when(a4Renderer.render(model)).thenReturn(pdf);

        mockMvc.perform(get("/api/orders/{orderId}/documents/a4", orderId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(content().bytes(pdf));

        verify(users).findById(user.getId());
        verify(documentService).load(orderId);
        verify(a4Renderer).render(model);
    }

    @Test
    void functionalAuthoritiesReachTheirCriticalRoutes() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID managedUserId = UUID.randomUUID();
        UUID adminTargetId = UUID.randomUUID();
        UserAccount paymentUser = activeUser();
        UserAccount deliveryUser = activeUser();
        UserAccount inventoryUser = activeUser();
        UserAccount userManager = activeUser();
        UserAccount administrator = activeUser();
        when(accountPaymentService.apply(any(), any())).thenReturn(new AccountPaymentService.PaymentResult(
            customerId, new BigDecimal("1.0000"), BigDecimal.ZERO, BigDecimal.ZERO, "OLDEST_FIRST", List.of()));

        mockMvc.perform(post("/api/customers/{customerId}/account-payments", customerId)
                .header("Authorization", bearer(paymentUser, "SALE_PAYMENT"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1.0000,\"method\":\"CASH\"}"))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/orders/{id}/delivery-attempts", orderId)
                .header("Authorization", bearer(deliveryUser, "SALE_DELIVER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\":\"FAILED\",\"observation\":\"No estaba\"}"))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/inventory/{productId}/adjustments", productId)
                .header("Authorization", bearer(inventoryUser, "STOCK_ADJUST"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1,\"reason\":\"Conteo\"}"))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/inventory/transfers")
                .header("Authorization", bearer(inventoryUser, "STOCK_ADJUST"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/inventory/depots")
                .header("Authorization", bearer(administrator, "ADMIN_ALL")))
            .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/users/{id}/block", managedUserId)
                .header("Authorization", bearer(userManager, "USER_MANAGE")))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/users/{id}/block", adminTargetId)
                .header("Authorization", bearer(administrator, "ADMIN_ALL")))
            .andExpect(status().isNoContent());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/users/{id}/role", adminTargetId)
                .header("Authorization", bearer(administrator, "ADMIN_ALL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"))
            .andExpect(status().isNoContent());

        verify(accountPaymentService).apply(any(), any());
        verify(deliveryLifecycleService).recordAttempt(any(), any());
        verify(inventoryCommandService).adjust(eq(productId), any(), eq("Conteo"));
        verify(userAdminService).blockUser(managedUserId);
        verify(userAdminService).blockUser(adminTargetId);
        verify(userAdminService).changeRole(adminTargetId, "ADMIN");
    }

    @Test
    void unrelatedFunctionalAuthoritiesCannotCrossCriticalRouteBoundaries() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        UserAccount seller = activeUser();
        UserAccount userManager = activeUser();

        mockMvc.perform(post("/api/customers/{customerId}/account-payments", customerId)
                .header("Authorization", bearer(seller, "ORDER_CREATE"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1.0000,\"method\":\"CASH\"}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/orders/{id}/delivery-attempts", orderId)
                .header("Authorization", bearer(seller, "ORDER_CREATE"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"result\":\"FAILED\"}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/inventory/{productId}/adjustments", productId)
                .header("Authorization", bearer(seller, "ORDER_CREATE"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1,\"reason\":\"Conteo\"}"))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/inventory/transfers")
                .header("Authorization", bearer(seller, "ORDER_CREATE"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/inventory/depots")
                .header("Authorization", bearer(seller, "ORDER_CREATE")))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/users/{id}/block", targetUserId)
                .header("Authorization", bearer(seller, "ORDER_CREATE")))
            .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                "/api/users/{id}/role", targetUserId)
                .header("Authorization", bearer(userManager, "USER_MANAGE"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(accountPaymentService, deliveryLifecycleService,
            inventoryCommandService, userAdminService, roleAdminService);
    }

    private String bearer(UserAccount user, String authority) {
        return "Bearer " + jwtService.issue(user, List.of(authority));
    }

    private UserAccount activeUser() {
        UUID id = UUID.randomUUID();
        UserAccount user = new UserAccount("security-test@example.com", "not-used-in-this-test");
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "version", 2L);
        when(users.findById(id)).thenReturn(Optional.of(user));
        return user;
    }
}
