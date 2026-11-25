package com.distribuidora.catalog;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.catalog.api.CatalogAdminDtos;
import com.distribuidora.catalog.application.BrandService;
import com.distribuidora.catalog.application.CatalogEntryInUseException;
import com.distribuidora.catalog.application.CategoryService;
import com.distribuidora.catalog.application.ProductCommandService;
import com.distribuidora.dashboard.application.ReadQueryService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import com.distribuidora.shared.security.TestCurrentUserAccess;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class ProductDescriptionPostgresTest {
    private static final String URL = System.getenv("POSTGRES_TEST_URL");
    private static final String USER = System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "distribuidora");
    private static final String PASSWORD = System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "distribuidora");

    @Test
    void migratesLegacyDescriptionsAndUsesCanonicalBrandNamesForWritesAndReads() throws Exception {
        withDatabase(dataSource -> {
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
            assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, created)).isZero();
            commands.update(created, new ProductCommandService.ProductInput("Rice 2 kg", null, null, BigDecimal.TEN, List.of(), category, brand));
            var brands = new BrandService(jdbc, mock(AuditService.class), TestCurrentUserAccess.admin(jdbc));
            brands.update(brand, new CatalogAdminDtos.UpdateBrandRequest("New Acme"));
            var rows = new ReadQueryService(jdbc, TestCurrentUserAccess.admin(jdbc)).products(0, 20, "New Acme Rice").content();
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()).containsEntry("name", "New Acme Rice 2 kg").containsEntry("description", "Rice 2 kg");
            assertThat(jdbc.queryForObject("select price from catalog.product_prices where product_id = ? and price_list_id = ?", BigDecimal.class, created, list)).isEqualByComparingTo("25");

            String newBrandName = "Local Brand";
            String newCategoryName = "Local Category";
            UUID newBrand = brands.create(new CatalogAdminDtos.CreateBrandRequest(newBrandName));
            var categories = new CategoryService(jdbc, mock(AuditService.class), TestCurrentUserAccess.admin(jdbc));
            UUID newCategory = categories.create(new CatalogAdminDtos.CreateCategoryRequest(newCategoryName));
            assertThat(brands.getById(newBrand).name()).isEqualTo(newBrandName);
            assertThat(categories.getById(newCategory).name()).isEqualTo(newCategoryName);

            assertThatThrownBy(() -> categories.deletePermanently(category)).isInstanceOf(CatalogEntryInUseException.class);
            assertThatThrownBy(() -> brands.deletePermanently(brand)).isInstanceOf(CatalogEntryInUseException.class);
            assertThat(categories.getById(category).productCount()).isEqualTo(2);
            assertThat(brands.getById(brand).productCount()).isEqualTo(2);

            UUID plan = UUID.randomUUID();
            jdbc.update("insert into app.commission_plans(id, name) values (?, 'Catalog retention test')", plan);
            jdbc.update("insert into app.commission_rules(plan_id, category_id, kind, value) values (?, ?, 'FIXED', 1)", plan, newCategory);
            jdbc.update("insert into app.commission_rules(plan_id, brand_id, kind, value) values (?, ?, 'FIXED', 1)", plan, newBrand);
            assertThatThrownBy(() -> categories.deletePermanently(newCategory)).isInstanceOf(CatalogEntryInUseException.class);
            assertThatThrownBy(() -> brands.deletePermanently(newBrand)).isInstanceOf(CatalogEntryInUseException.class);
            assertThat(categories.getById(newCategory).productCount()).isZero();
            assertThat(brands.getById(newBrand).productCount()).isZero();
            jdbc.update("delete from app.commission_plans where id = ?", plan);

            categories.deletePermanently(newCategory);
            brands.deletePermanently(newBrand);
            assertThatThrownBy(() -> categories.getById(newCategory)).isInstanceOf(EmptyResultDataAccessException.class);
            assertThatThrownBy(() -> brands.getById(newBrand)).isInstanceOf(EmptyResultDataAccessException.class);

            var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), null,
                List.of(new SimpleGrantedAuthority("ADMIN_ALL"), new SimpleGrantedAuthority("STOCK_ADJUST"))));
            try {
                UUID stocked = transactions.execute(status -> commands.create(new ProductCommandService.ProductInput(
                    "Rice 500 g", null, null, BigDecimal.TEN, List.of(new ProductCommandService.ProductPriceInput(list, BigDecimal.valueOf(25))),
                    category, brand, null, new BigDecimal("12.5"))));
                assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances where product_id = ?", BigDecimal.class, stocked)).isEqualByComparingTo("12.5");
                assertThat(jdbc.queryForList("select movement_type, quantity, reason from inventory.stock_movements where product_id = ?", stocked))
                    .singleElement().satisfies(movement -> {
                        assertThat(movement).containsEntry("movement_type", "MANUAL_ADJUSTMENT").containsEntry("reason", "Stock inicial");
                        assertThat((BigDecimal) movement.get("quantity")).isEqualByComparingTo("12.5");
                    });
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    private static void withDatabase(Consumer<DriverManagerDataSource> test) throws Exception {
        String database = "product_desc_" + UUID.randomUUID().toString().replace("-", "");
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
