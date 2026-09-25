package com.distribuidora.pricing;

import com.distribuidora.pricing.api.CommercialDiscountRuleCommandController;
import com.distribuidora.pricing.application.CommercialDiscountRuleCommandService;
import com.distribuidora.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.access.prepost.PreAuthorize;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommercialDiscountRuleCommandControllerTest {
    private final CommercialDiscountRuleCommandService service = mock(CommercialDiscountRuleCommandService.class);
    private final MockMvc mockMvc = standaloneSetup(new CommercialDiscountRuleCommandController(service))
        .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void acceptsOrderRuleAndReturnsCreatedIdentifier() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any())).thenReturn(id);

        mockMvc.perform(post("/api/pricing/discount-rules").contentType(APPLICATION_JSON).content("""
                {"code":"ORDER5","description":"5% por orden","kind":"ORDER","percent":5.0000,
                 "customerId":null,"priceListId":null,"productId":null,
                 "validFrom":"2026-09-24","validUntil":null,"priority":0}
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(id.toString()));

        verify(service).create(org.mockito.ArgumentMatchers.argThat(input ->
            input.kind().equals("ORDER") && input.percent().compareTo(new java.math.BigDecimal("5.0000")) == 0));
    }

    @Test
    void protectsEveryRuleMutationWithAdminAll() throws Exception {
        for (String method : new String[]{"create", "update", "setStatus"}) {
            PreAuthorize annotation = java.util.Arrays.stream(CommercialDiscountRuleCommandController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(method))
                .map(candidate -> candidate.getAnnotation(PreAuthorize.class))
                .filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
            assertThat(annotation.value()).isEqualTo("hasAuthority('ADMIN_ALL')");
        }
    }
}
