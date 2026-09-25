package com.distribuidora.pricing;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.pricing.application.CommercialDiscountRuleCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CommercialDiscountRuleCommandServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final CommercialDiscountRuleCommandService service = new CommercialDiscountRuleCommandService(jdbc, audit);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsAndAuditsNormalizedLineRule() {
        UUID actorId = authenticate();
        UUID customerId = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return !sql.contains("where code = ?");
        });
        when(jdbc.queryForObject(eq("select (current_timestamp at time zone 'America/Argentina/Buenos_Aires')::date"),
            eq(LocalDate.class))).thenReturn(LocalDate.of(2026, 9, 24));

        UUID id = service.create(new CommercialDiscountRuleCommandService.RuleInput(
            " regalo_10 ", " Descuento por producto ", "line", new BigDecimal("10.0000"),
            customerId, listId, productId, null, null, null));

        assertThat(id).isNotNull();
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("insert into catalog.commercial_discount_rules"),
            eq(id), eq("REGALO_10"), eq("Descuento por producto"), eq("LINE"), eq(new BigDecimal("10.0000")),
            eq(customerId), eq(listId), eq(productId), eq(0), eq(LocalDate.of(2026, 9, 24)), eq(null), any(), any());
        verify(audit).record(eq(actorId), eq("DISCOUNT_RULE_CREATE"), eq("DISCOUNT_RULE"), eq(id.toString()),
            eq("SUCCESS"), eq(Map.of("code", "REGALO_10", "kind", "LINE", "percent", new BigDecimal("10.0000"),
                "validFrom", "2026-09-24", "priority", 0)));
    }

    @Test
    void rejectsInvalidRuleScopeAndDateWindowBeforeWriting() {
        var missingProduct = new CommercialDiscountRuleCommandService.RuleInput(
            "ORDER10", "Orden", "LINE", BigDecimal.ONE, null, null, null,
            LocalDate.of(2026, 9, 24), null, 0);
        assertThatThrownBy(() -> service.create(missingProduct))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requiere productId");
        verifyNoInteractions(jdbc, audit);

        var backwardsDates = new CommercialDiscountRuleCommandService.RuleInput(
            "ORDER10", "Orden", "ORDER", BigDecimal.ONE, null, null, null,
            LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 24), 0);
        assertThatThrownBy(() -> service.create(backwardsDates))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("validUntil");
        verifyNoInteractions(jdbc, audit);
    }

    @Test
    void validatesStatusAndReportsMissingRule() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.setStatus(id, "DELETED"))
            .isInstanceOf(IllegalArgumentException.class);
        when(jdbc.update(anyString(), eq("INACTIVE"), any(), eq(id))).thenReturn(0);
        assertThatThrownBy(() -> service.setStatus(id, "INACTIVE"))
            .isInstanceOf(EmptyResultDataAccessException.class);
    }

    private UUID authenticate() {
        UUID id = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(id.toString(), null));
        return id;
    }
}
