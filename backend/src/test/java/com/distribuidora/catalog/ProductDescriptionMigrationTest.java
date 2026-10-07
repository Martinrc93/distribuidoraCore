package com.distribuidora.catalog;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.catalog.api.CatalogAdminDtos;
import com.distribuidora.catalog.application.BrandService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProductDescriptionMigrationTest {
    @Test
    void migratesNamesWithoutDuplicatingBrandsAndPreservesRelatedDataAndHistoricalNames() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create schema catalog");
        jdbc.execute("create schema inventory");
        jdbc.execute("create schema orders");
        jdbc.execute("create table catalog.brands(id uuid primary key, name varchar(100), status varchar)");
        jdbc.execute("create table catalog.products(id uuid primary key, name varchar(200), brand_id uuid references catalog.brands(id), category_id uuid)");
        jdbc.execute("create table inventory.inventory_balances(product_id uuid references catalog.products(id), quantity decimal(19,4))");
        jdbc.execute("create table catalog.product_prices(product_id uuid references catalog.products(id), price decimal(19,4))");
        jdbc.execute("create table orders.order_items(product_id uuid references catalog.products(id), product_name varchar(301))");
        UUID brand = UUID.randomUUID();
        UUID otherBrand = UUID.randomUUID();
        UUID category = UUID.randomUUID();
        jdbc.update("insert into catalog.brands values (?, 'Acme', 'ACTIVE'), (?, 'Other', 'ACTIVE')", brand, otherBrand);
        UUID plain = product(jdbc, "Flour", brand, category);
        UUID prefixed = product(jdbc, "  acme Rice  ", brand, category);
        UUID unbranded = product(jdbc, "Legacy product", null, category);
        UUID other = product(jdbc, "Unchanged", otherBrand, category);
        jdbc.update("insert into inventory.inventory_balances values (?, -0.5)", plain);
        jdbc.update("insert into catalog.product_prices values (?, 12)", plain);
        jdbc.update("insert into orders.order_items values (?, 'Flour')", plain);

        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V34__add_product_description.sql")).execute(dataSource);

        assertThat(jdbc.queryForMap("select name, description, brand_id, category_id from catalog.products where id = ?", plain))
            .containsEntry("NAME", "Acme Flour").containsEntry("DESCRIPTION", "Flour")
            .containsEntry("BRAND_ID", brand).containsEntry("CATEGORY_ID", category);
        assertThat(jdbc.queryForMap("select name, description from catalog.products where id = ?", prefixed))
            .containsEntry("NAME", "Acme Rice").containsEntry("DESCRIPTION", "Rice");
        assertThat(jdbc.queryForMap("select name, description from catalog.products where id = ?", unbranded))
            .containsEntry("NAME", "Legacy product").containsEntry("DESCRIPTION", "Legacy product");
        assertThat(jdbc.queryForObject("select quantity from inventory.inventory_balances", BigDecimal.class)).isEqualByComparingTo("-0.5");
        assertThat(jdbc.queryForObject("select price from catalog.product_prices", BigDecimal.class)).isEqualByComparingTo("12");

        var brands = new BrandService(jdbc, mock(AuditService.class), null);
        brands.update(brand, new CatalogAdminDtos.UpdateBrandRequest("New Brand"));
        assertThat(jdbc.queryForObject("select name from catalog.products where id = ?", String.class, plain)).isEqualTo("New Brand Flour");
        assertThat(jdbc.queryForObject("select name from catalog.products where id = ?", String.class, prefixed)).isEqualTo("New Brand Rice");
        assertThat(jdbc.queryForObject("select name from catalog.products where id = ?", String.class, other)).isEqualTo("Other Unchanged");
        assertThat(jdbc.queryForObject("select product_name from orders.order_items", String.class)).isEqualTo("Flour");
        jdbc.update("update catalog.products set name = ? where id = ?", "x".repeat(301), plain);
    }

    private UUID product(JdbcTemplate jdbc, String name, UUID brand, UUID category) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products values (?, ?, ?, ?)", id, name, brand, category);
        return id;
    }
}
