package com.distribuidora.catalog;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.catalog.api.CatalogAdminDtos;
import com.distribuidora.catalog.application.BrandService;
import com.distribuidora.catalog.application.ProductCommandService;
import com.distribuidora.dashboard.application.ReadQueryService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class ProductDescriptionPostgresTest {
    @Test
    void migratesLegacyDescriptionsAndUsesCanonicalBrandNamesForWritesAndReads() {
        var dataSource = new DriverManagerDataSource(System.getenv("POSTGRES_TEST_URL"),
            System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "distribuidora"),
            System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "distribuidora"));
        Flyway.configure().dataSource(dataSource).target("33").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        UUID brand = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        UUID legacy = UUID.randomUUID();
        jdbc.update("insert into catalog.brands(id, name, code, status, created_at) values (?, 'Acme', ?, 'ACTIVE', current_timestamp)", brand, "TEST_" + brand);
        jdbc.update("insert into catalog.categories(id, name, code, status, created_at) values (?, 'Test Grocery', ?, 'ACTIVE', current_timestamp)", category, "TEST_" + category);
        jdbc.update("insert into catalog.products(id, name, category_id, brand_id, cost, created_at) values (?, 'Acme Flour', ?, ?, 10, current_timestamp)", legacy, category, brand);
        jdbc.update("insert into inventory.inventory_balances(product_id, quantity, updated_at) values (?, -0.5, current_timestamp)", legacy);
        Flyway.configure().dataSource(dataSource).load().migrate();
        assertThat(jdbc.queryForObject("select description from catalog.products where id = ?", String.class, legacy)).isEqualTo("Flour");
        assertThat(jdbc.queryForObject("select name from catalog.products where id = ?", String.class, legacy)).isEqualTo("Acme Flour");
        assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, legacy)).isEqualByComparingTo("-0.5");
        var commands = new ProductCommandService(jdbc, mock(AuditService.class));
        UUID list = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID created = commands.create(new ProductCommandService.ProductInput("  Rice 1 kg  ", null, null,
            BigDecimal.TEN, List.of(new ProductCommandService.ProductPriceInput(list, BigDecimal.valueOf(25))), category, brand));
        commands.update(created, new ProductCommandService.ProductInput("Rice 2 kg", null, null, BigDecimal.TEN, List.of(), category, brand));
        var brands = new BrandService(jdbc, mock(AuditService.class), null);
        brands.update(brand, new CatalogAdminDtos.UpdateBrandRequest("New Acme"));
        var rows = new ReadQueryService(jdbc).products(0, 20, "New Acme Rice").content();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("name", "New Acme Rice 2 kg").containsEntry("description", "Rice 2 kg");
        assertThat(jdbc.queryForObject("select price from catalog.product_prices where product_id = ?", BigDecimal.class, created)).isEqualByComparingTo("25");
    }
}
