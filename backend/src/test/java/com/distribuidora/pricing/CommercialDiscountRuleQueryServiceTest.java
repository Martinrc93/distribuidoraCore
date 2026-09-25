package com.distribuidora.pricing;

import com.distribuidora.pricing.application.CommercialDiscountRuleQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommercialDiscountRuleQueryServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CommercialDiscountRuleQueryService service = new CommercialDiscountRuleQueryService(jdbc);

    @Test
    void returnsPagedRuleManagementProjection() {
        Map<String, Object> rule = Map.of("code", "LINE10", "kind", "LINE");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(rule));
        when(jdbc.queryForObject(anyString(), eq(Number.class))).thenReturn(5);

        var response = service.rules(1, 2);

        assertThat(response.content()).containsExactly(rule);
        assertThat(response.totalElements()).isEqualTo(5);
        verify(jdbc).queryForList(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("commercial_discount_rules")
            && sql.contains("valid_from") && sql.contains("priceListCode")), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void selectsApplicableLineRuleUsingPriorityAndScopeSpecificity() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID priceListId = UUID.randomUUID();
        var selected = new CommercialDiscountRuleQueryService.DiscountSnapshot(
            UUID.randomUUID(), new BigDecimal("7.5000"));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(selected));

        var resolved = service.lineDiscount(customerId, productId, priceListId);

        assertThat(resolved).contains(selected);
        verify(jdbc).query(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("kind = 'LINE'")
                && sql.contains("priority desc") && sql.contains("customer_id is not null")
                && sql.contains("America/Argentina/Buenos_Aires")),
            any(RowMapper.class), eq(productId), eq(customerId), eq(priceListId));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void resolvesOrderRuleAgainstCustomerListOrGeneralFallback() {
        UUID customerId = UUID.randomUUID();
        UUID generalListId = UUID.randomUUID();
        var selected = new CommercialDiscountRuleQueryService.DiscountSnapshot(
            UUID.randomUUID(), new BigDecimal("5.0000"));
        when(jdbc.queryForObject(anyString(), eq(UUID.class), eq(null), eq(customerId))).thenReturn(generalListId);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(customerId), eq(generalListId))).thenReturn(List.of(selected));

        assertThat(service.orderDiscount(customerId, null)).contains(selected);
    }
}
