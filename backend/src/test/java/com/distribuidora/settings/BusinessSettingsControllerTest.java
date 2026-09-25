package com.distribuidora.settings;

import com.distribuidora.settings.api.BusinessSettingsController;
import com.distribuidora.settings.api.BusinessSettingsDtos;
import com.distribuidora.settings.application.BusinessSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessSettingsControllerTest {
    private final BusinessSettingsService service = mock(BusinessSettingsService.class);
    private final BusinessSettingsController controller = new BusinessSettingsController(service);

    @Test
    void delegatesGetAndUpdateCreditLimit() throws Exception {
        UUID actor = UUID.randomUUID();
        var response = new BusinessSettingsDtos.CreditLimitResponse(new BigDecimal("100.0000"), true, null, actor);
        var request = new BusinessSettingsDtos.CreditLimitRequest(new BigDecimal("100.0000"));
        when(service.creditLimit()).thenReturn(response);
        when(service.updateCreditLimit(request)).thenReturn(response);

        assertThat(controller.creditLimit()).isEqualTo(response);
        assertThat(controller.updateCreditLimit(request).getStatusCode().value()).isEqualTo(200);
        verify(service).creditLimit();
        verify(service).updateCreditLimit(request);

        var get = BusinessSettingsController.class.getDeclaredMethod("creditLimit");
        var put = BusinessSettingsController.class.getDeclaredMethod("updateCreditLimit", BusinessSettingsDtos.CreditLimitRequest.class);
        assertThat(get.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");
        assertThat(put.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('ADMIN_ALL')");
    }
}
