package com.distribuidora.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;
import static org.assertj.core.api.Assertions.assertThat;
import org.mockito.ArgumentCaptor;
import java.util.UUID;

class DemoDataSeederTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final DemoDataSeeder seeder = new DemoDataSeeder(jdbc, passwordEncoder, true, "secret");

    @Test
    void repairsProductPricesWhenDemoSeedAlreadyExists() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("demo-v1"))).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(java.util.UUID.class))).thenReturn(java.util.UUID.randomUUID());

        seeder.run(mock(ApplicationArguments.class));

        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("INSERT INTO catalog.product_prices"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture());
        assertThat(sql.getAllValues()).anyMatch(value -> value.contains("INSERT INTO catalog.product_prices")
            && value.contains("FROM catalog.price_lists")
            && value.contains("CROSS JOIN catalog.products")
            && value.contains("status = 'ACTIVE'")
            && value.contains("ON CONFLICT (price_list_id, product_id) DO NOTHING"));
    }

    @Test
    void repairsMissingSeedDebitAndReconcilesSeededCustomerBalance() {
        UUID saleId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("demo-v1"))).thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(UUID.class))).thenReturn(UUID.randomUUID());
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
            .thenReturn(java.util.List.of(new DemoDataSeeder.RepairLedgerEntry(
                saleId, customerId, new java.math.BigDecimal("12.5000"),
                java.sql.Timestamp.valueOf("2026-09-19 10:00:00"))));

        seeder.run(mock(ApplicationArguments.class));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).anyMatch(value -> value.contains("customer.account_ledger")
            && value.contains("'DEBIT'"));
        ArgumentCaptor<String> balanceSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).update(balanceSql.capture());
        assertThat(balanceSql.getAllValues()).anyMatch(value -> value.contains("customer.customers c")
            && value.contains("customer.account_ledger")
            && value.contains("DEBIT")
            && value.contains("CREDIT"));
    }

    @Test
    void seedsV6ItemSnapshots() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("demo-v1"))).thenReturn(false);
        when(passwordEncoder.encode("secret")).thenReturn("hash");
        when(jdbc.queryForMap(anyString(), any()))
            .thenReturn(java.util.Map.of("name", "Producto", "price", new java.math.BigDecimal("10.0000")));

        seeder.run(mock(ApplicationArguments.class));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).filteredOn(value -> value.contains("orders.order_items"))
            .allSatisfy(value -> assertThat(value).contains("price_list_id", "price_list_code", "line_discount_percent"));
        assertThat(sql.getAllValues()).filteredOn(value -> value.contains("sale.sale_items"))
            .allSatisfy(value -> assertThat(value).contains("price_list_id", "price_list_code", "line_discount_percent"));
        assertThat(sql.getAllValues()).anyMatch(value -> value.contains("customer.account_ledger")
            && value.contains("'DEBIT'"));
    }
}
