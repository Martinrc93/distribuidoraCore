package com.distribuidora.order;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class OrderFulfillmentMigrationPostgresTest {
    @Test
    void migratesHistoricalStockWithoutInventingPreparationMetadataAndSeedsIndependentPermissions() throws Exception {
        String url = System.getenv("POSTGRES_TEST_URL");
        String user = System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "distribuidora");
        String password = System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "distribuidora");
        String database = "fulfillment_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            statement.execute("create database " + database);
            try {
                var source = new DriverManagerDataSource(url.substring(0, url.lastIndexOf('/') + 1) + database, user, password);
                Flyway.configure().dataSource(source).target("41").load().migrate();
                var jdbc = new JdbcTemplate(source);
                UUID customer = UUID.randomUUID();
                jdbc.update("insert into customer.customers(id, business_name, created_at) values (?, 'Migration customer', now())", customer);
                for (String status : new String[]{"CONFIRMED", "DELIVERED", "CANCELLED"}) {
                    jdbc.update("insert into orders.orders(id, order_number, customer_id, status, subtotal, discount, total, created_at) values (?, ?, ?, ?, 10, 0, 10, now())",
                        UUID.randomUUID(), status, customer, status);
                }
                Flyway.configure().dataSource(source).load().migrate();
                assertThat(jdbc.queryForObject("select count(*) from orders.orders where status <> 'CANCELLED' and fulfillment_status = 'PREPARED' and legacy_fulfillment and prepared_at is null and prepared_by_user_id is null", Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("select fulfillment_status from orders.orders where status = 'CANCELLED'", String.class)).isEqualTo("PENDING");
                assertThat(jdbc.queryForObject("select count(*) from inventory.stock_movements", Integer.class)).isZero();
                jdbc.update("insert into orders.orders(id, order_number, customer_id, status, subtotal, discount, total, created_at) values (?, 'NEW', ?, 'CONFIRMED', 10, 0, 10, now())", UUID.randomUUID(), customer);
                assertThat(jdbc.queryForObject("select fulfillment_status from orders.orders where order_number = 'NEW'", String.class)).isEqualTo("PENDING");
                assertThat(jdbc.queryForList("select r.code from identity.role_permissions rp join identity.roles r on r.id = rp.role_id join identity.permissions p on p.id = rp.permission_id where p.code = 'ORDER_PREPARE'", String.class)).containsExactlyInAnyOrder("ADMIN", "SELLER");
                assertThat(jdbc.queryForList("select r.code from identity.role_permissions rp join identity.roles r on r.id = rp.role_id join identity.permissions p on p.id = rp.permission_id where p.code = 'ORDER_REVIEW'", String.class)).containsExactly("ADMIN");
            } finally { statement.execute("drop database " + database); }
        }
    }
}
