package com.distribuidora.inventory;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class SingleInventoryMigrationIntegrationTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void consolidatesBalancesAndPreservesBusinessRecordsWithoutDepotAttribution() {
        Flyway v23 = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("23"))
            .load();
        v23.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        UUID productId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID secondDepotId = UUID.randomUUID();
        UUID centralDepotId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        jdbc.update("insert into catalog.products(id, sku, name, category, presentation, cost, created_at) values (?, ?, ?, ?, ?, 1, now())",
            productId, "MIG-" + productId, "Migration product", "General", "Unit");
        jdbc.update("insert into customer.customers(id, business_name, tax_id, created_at) values (?, ?, ?, now())",
            customerId, "Migration customer", "TAX-" + customerId);
        jdbc.update("insert into inventory.depots(id, code, name, status, is_default, created_at, updated_at) values (?, ?, ?, 'ACTIVE', false, now(), now())",
            secondDepotId, "MIG-" + secondDepotId, "Migration depot");
        jdbc.update("insert into inventory.inventory_balances(depot_id, product_id, quantity, updated_at) values (?, ?, 3.5, now() - interval '1 day'), (?, ?, 2.0, now())",
            centralDepotId, productId, secondDepotId, productId);
        jdbc.update("insert into inventory.stock_movements(id, depot_id, product_id, movement_type, quantity, reason, created_at) values (?, ?, ?, 'SALE', -1.0, 'Historical sale', now())",
            movementId, secondDepotId, productId);
        jdbc.update("insert into orders.orders(id, order_number, customer_id, status, subtotal, discount, total, created_at, depot_id) values (?, ?, ?, 'CONFIRMED', 2, 0, 2, now(), ?)",
            orderId, "ORD-MIG", customerId, secondDepotId);
        jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, created_at, depot_id) values (?, ?, ?, ?, 'CONFIRMED', 2, now(), ?)",
            saleId, "SAL-MIG", orderId, customerId, secondDepotId);

        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load()
            .migrate();

        var balances = jdbc.queryForList("select quantity from inventory.inventory_balances where product_id = ?",
            BigDecimal.class, productId);
        assertThat(balances).hasSize(1);
        assertThat(balances.getFirst()).isEqualByComparingTo("5.5");
        assertThat(jdbc.queryForObject("select count(*) from inventory.inventory_balances where product_id = ?",
            Integer.class, productId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reason from inventory.stock_movements where id = ?", String.class, movementId))
            .isEqualTo("Historical sale");
        assertThat(jdbc.queryForObject("select order_number from orders.orders where id = ?", String.class, orderId))
            .isEqualTo("ORD-MIG");
        assertThat(jdbc.queryForObject("select sale_number from sale.sales where id = ?", String.class, saleId))
            .isEqualTo("SAL-MIG");
        for (String[] table : new String[][]{
            {"inventory", "inventory_balances"}, {"inventory", "stock_movements"},
            {"orders", "orders"}, {"sale", "sales"}
        }) {
            assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema = ? and table_name = ? and column_name = 'depot_id'",
                Integer.class, table[0], table[1])).isZero();
        }
        assertThat(jdbc.queryForObject("select to_regclass('inventory.depots')", String.class)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint c join pg_class t on t.oid = c.conrelid join pg_namespace n on n.oid = t.relnamespace where n.nspname = 'inventory' and t.relname = 'inventory_balances' and c.contype = 'p' and pg_get_constraintdef(c.oid) = 'PRIMARY KEY (product_id)'",
            Integer.class)).isEqualTo(1);
    }
}
