package com.distribuidora.security;

import com.distribuidora.catalog.api.BrandController;
import com.distribuidora.catalog.api.CategoryController;
import com.distribuidora.catalog.application.BrandService;
import com.distribuidora.catalog.application.CategoryService;
import com.distribuidora.config.SecurityConfig;
import com.distribuidora.dashboard.api.ReadQueryController;
import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.identity.domain.UserAccount;
import com.distribuidora.identity.infrastructure.UserAccountRepository;
import com.distribuidora.identity.security.JwtAuthenticationFilter;
import com.distribuidora.identity.security.JwtService;
import com.distribuidora.pricing.api.PricingQueryController;
import com.distribuidora.pricing.application.PricingQueryService;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.web.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
    ReadQueryController.class,
    PricingQueryController.class,
    BrandController.class,
    CategoryController.class
})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class})
@TestPropertySource(properties = {
    "app.security.jwt-secret=0123456789abcdef0123456789abcdef",
    "app.security.jwt-issuer=security-read-test"
})
class ReadRoutesSecurityAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private UserAccountRepository users;

    @MockitoBean
    private ReadQueryService readQueryService;

    @MockitoBean
    private PricingQueryService pricingQueryService;

    @MockitoBean
    private CurrentUserAccess currentUserAccess;

    @MockitoBean
    private BrandService brandService;

    @MockitoBean
    private CategoryService categoryService;

    @Test
    void authenticatedUserWithoutPermissionsIsForbiddenFromReadRoutes() throws Exception {
        UserAccount emptyUser = activeUser();
        String noPerms = "Bearer " + jwtService.issue(emptyUser, List.of());

        for (String path : List.of(
            "/api/customers",
            "/api/customers/filter-options",
            "/api/products",
            "/api/orders",
            "/api/orders/" + UUID.randomUUID(),
            "/api/orders/by-number/000100001",
            "/api/sales",
            "/api/sales/" + UUID.randomUUID(),
            "/api/sales/filter-options",
            "/api/payments",
            "/api/brands",
            "/api/brands/" + UUID.randomUUID(),
            "/api/categories",
            "/api/categories/" + UUID.randomUUID(),
            "/api/pricing/lists"
        )) {
            mockMvc.perform(get(path).header("Authorization", noPerms))
                .andExpect(status().isForbidden());
        }

        verifyNoInteractions(readQueryService, pricingQueryService, brandService, categoryService);
    }

    @Test
    void sellerWithOrderCreateCanReadPriceListsButCannotReadAdminCatalogOrPayments() throws Exception {
        UserAccount seller = activeUser();
        String sellerToken = "Bearer " + jwtService.issue(seller, List.of("ORDER_CREATE"));
        when(pricingQueryService.lists(0, 20, false)).thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/brands").header("Authorization", sellerToken)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/categories").header("Authorization", sellerToken)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/payments").header("Authorization", sellerToken)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/pricing/lists").header("Authorization", sellerToken)).andExpect(status().isOk());

        verify(pricingQueryService).lists(0, 20, false);
        verifyNoInteractions(brandService, categoryService, readQueryService);
    }

    private UserAccount activeUser() {
        UUID id = UUID.randomUUID();
        UserAccount user = new UserAccount("read-sec-test@example.com", "not-used");
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "sessionVersion", 1L);
        when(users.findById(id)).thenReturn(Optional.of(user));
        return user;
    }
}
