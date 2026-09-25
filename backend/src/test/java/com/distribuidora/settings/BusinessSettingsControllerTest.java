package com.distribuidora.settings;

import com.distribuidora.settings.api.BusinessSettingsController;
import com.distribuidora.settings.api.BusinessSettingsDtos;
import com.distribuidora.settings.application.BusinessSettingsService;
import com.distribuidora.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class BusinessSettingsControllerTest {
    private final BusinessSettingsService service = mock(BusinessSettingsService.class);
    private final BusinessSettingsController controller = new BusinessSettingsController(service);
    private final MockMvc mockMvc = standaloneSetup(controller)
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void delegatesGetAndUpdateCreditLimit() throws Exception {
        UUID actor = UUID.randomUUID();
        var response = new BusinessSettingsDtos.CreditLimitResponse(new BigDecimal("100.0000"), true, null, actor);
        var result = new BusinessSettingsService.CreditLimitResult(new BigDecimal("100.0000"), true, null, actor);
        var request = new BusinessSettingsDtos.CreditLimitRequest(new BigDecimal("100.0000"));
        when(service.creditLimit()).thenReturn(result);
        when(service.updateCreditLimit(request)).thenReturn(result);

        assertThat(controller.creditLimit()).isEqualTo(response);
        assertThat(controller.updateCreditLimit(request).getStatusCode().value()).isEqualTo(200);
        verify(service).creditLimit();
        verify(service).updateCreditLimit(request);

        var get = BusinessSettingsController.class.getDeclaredMethod("creditLimit");
        var put = BusinessSettingsController.class.getDeclaredMethod("updateCreditLimit", BusinessSettingsDtos.CreditLimitRequest.class);
        assertThat(get.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");
        assertThat(put.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");
    }

    @Test
    void httpUpdateBindsCreditLimitAndSerializesResponse() throws Exception {
        UUID actor = UUID.randomUUID();
        var request = new BusinessSettingsDtos.CreditLimitRequest(new BigDecimal("125.50"));
        when(service.updateCreditLimit(request)).thenReturn(new BusinessSettingsService.CreditLimitResult(
            new BigDecimal("125.5000"), true, null, actor));

        mockMvc.perform(put("/api/settings/credit-limit")
                .contentType(APPLICATION_JSON)
                .content("""
                    {"creditLimit":125.50}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.creditLimit").value(125.5))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.updatedBy").value(actor.toString()));

        verify(service).updateCreditLimit(request);
    }

    @Test
    void httpUpdateRejectsExcessFractionalPrecisionBeforeCallingService() throws Exception {
        mockMvc.perform(put("/api/settings/credit-limit")
                .contentType(APPLICATION_JSON)
                .content("""
                    {"creditLimit":12.12345}
                    """))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
