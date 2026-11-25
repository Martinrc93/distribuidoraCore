package com.distribuidora.catalog;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.catalog.application.ProductCommandService;
import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.pricing.application.PricingQueryService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.distribuidora.shared.security.TestCurrentUserAccess;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class ProductSkuRemovalPostgresTest {
    private static final String URL = System.getenv("POSTGRES_TEST_URL");
    private static final String USER = System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "distribuidora");
    private static final String PASSWORD = System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "distribuidora");

    @Test
    void removesLegacyColumnPreservingProductsPricesAndInventoryAndSupportsWritesWithoutIt() throws Exception {
        withDatabase(datasource -> {
            Flyway.configure().dataSource(datasource).target("30").load().migrate();
            var jdbc = new JdbcTemplate(datasource);
            UUID id = UUID.randomUUID();
            UUID listId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            jdbc.update("insert into catalog.products(id, sku, name, category, cost, created_at) values (?, 'LEGACY', 'Legacy product', 'General', 10, current_timestamp)", id);
            jdbc.update("insert into inventory.inventory_balances(product_id, quantity, updated_at) values (?, 12, current_timestamp)", id);
            jdbc.update("insert into catalog.product_prices(price_list_id, product_id, price, created_at, updated_at) values (?, ?, 25, current_timestamp, current_timestamp)", listId, id);
            jdbc.update("insert into catalog.product_price_history(id, price_list_id, product_id, price, effective_on, created_at, updated_at) values (?, ?, ?, 25, current_date, current_timestamp, current_timestamp)", UUID.randomUUID(), listId, id);

            Flyway.configure().dataSource(datasource).load().migrate();
            assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = 'catalog' and table_name = 'products' and column_name = 'sku'", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select name from catalog.products where id = ?", String.class, id)).isEqualTo("Legacy product");
            assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, id)).isEqualByComparingTo("12");
            var queries = new ReadQueryService(jdbc, TestCurrentUserAccess.admin(jdbc));
            assertThat(queries.products(0, 100, "Legacy", false).content().getFirst()).containsEntry("id", id).doesNotContainKeys("sku", "stock");
            assertThat(queries.products(0, 100, "Legacy").content().getFirst()).containsKey("stock").doesNotContainKey("sku");
            assertThat(new PricingQueryService(jdbc).prices(listId, 0, 100).content().getFirst()).containsEntry("productId", id).doesNotContainKey("sku");
            assertThat(jdbc.queryForObject("select price from catalog.product_prices where product_id = ? and price_list_id = ?", BigDecimal.class, id, listId)).isEqualByComparingTo("25");

            var commands = new ProductCommandService(jdbc, mock(AuditService.class));
            UUID categoryId = UUID.randomUUID();
            UUID brandId = UUID.randomUUID();
            jdbc.update("insert into catalog.categories(id, name, code, status, created_at) values (?, ?, ?, 'ACTIVE', current_timestamp)", categoryId, "Test category " + categoryId, "TEST_" + categoryId);
            jdbc.update("insert into catalog.brands(id, name, code, status, created_at) values (?, ?, ?, 'ACTIVE', current_timestamp)", brandId, "Test brand " + brandId, "TEST_" + brandId);
            UUID created = commands.create(new ProductCommandService.ProductInput("New product", "General", "Unit",
                BigDecimal.TEN, List.of(new ProductCommandService.ProductPriceInput(listId, BigDecimal.valueOf(25))), categoryId, brandId));
            commands.update(created, new ProductCommandService.ProductInput("Updated product", "General", "Unit", BigDecimal.TEN, List.of(), categoryId, brandId));
            assertThat(jdbc.queryForObject("select name from catalog.products where id = ?", String.class, created)).isEqualTo("Test brand " + brandId + " Updated product");
        });
    }

    private static void withDatabase(Consumer<DriverManagerDataSource> test) throws Exception {
        String database = "product_sku_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(URL, USER, PASSWORD); var statement = connection.createStatement()) {
            statement.execute("create database " + database);
            try {
                test.accept(new DriverManagerDataSource(URL.substring(0, URL.lastIndexOf('/') + 1) + database, USER, PASSWORD));
            } finally {
                statement.execute("drop database " + database + " with (force)");
            }
        }
    }
}
