package com.distribuidora.settings;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.settings.api.BusinessSettingsDtos;
import com.distribuidora.settings.application.BusinessSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BusinessSettingsServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final BusinessSettingsService service = new BusinessSettingsService(jdbc, audit);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminUpdatesGlobalLimitAndCreatesAudit() {
        UUID actor = authenticate("ADMIN_ALL");
        when(jdbc.queryForObject(contains("select credit_limit"), eq(BigDecimal.class), any(Object[].class)))
            .thenReturn(new BigDecimal("100.0000"));

        var response = service.updateCreditLimit(new BusinessSettingsDtos.CreditLimitRequest(new BigDecimal("75.5000")));

        assertThat(response.creditLimit()).isEqualByComparingTo("75.5000");
        assertThat(response.enabled()).isTrue();
        verify(jdbc).update(contains("update app.business_settings"), any(Object[].class));
        verify(audit).recordWithinTransaction(eq(actor), eq("GLOBAL_CREDIT_LIMIT_UPDATE"), eq("BUSINESS_SETTINGS"),
            eq("1"), eq("SUCCESS"), anyMap());
    }

    @Test
    void nonAdminCannotReadSettings() {
        authenticate("SALE_DELIVER");
        assertThatThrownBy(service::creditLimit).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(jdbc, audit);
    }

    @Test
    void rejectsNegativeLimitBeforeDatabaseAccess() {
        authenticate("ADMIN_ALL");
        assertThatThrownBy(() -> service.updateCreditLimit(new BusinessSettingsDtos.CreditLimitRequest(new BigDecimal("-1"))))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc, audit);
    }

    private UUID authenticate(String... authorities) {
        UUID actor = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken(actor.toString(), "n/a", authorities));
        return actor;
    }
}
