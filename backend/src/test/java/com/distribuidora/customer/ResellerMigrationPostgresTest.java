package com.distribuidora.customer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class ResellerMigrationPostgresTest {
    @Test
    void databaseKeepsResellerAssociationsExclusiveAndWithoutChains() throws Exception {
        String url = System.getenv("POSTGRES_TEST_URL");
        String user = System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "distribuidora");
        String password = System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "distribuidora");
        String database = "reseller_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            statement.execute("create database " + database);
            try {
                var source = new DriverManagerDataSource(url.substring(0, url.lastIndexOf('/') + 1) + database, user, password);
                Flyway.configure().dataSource(source).target("51").load().migrate();
                var jdbc = new JdbcTemplate(source);
                UUID existing = customer(jdbc, "Existing");
                Flyway.configure().dataSource(source).load().migrate();

                assertThat(jdbc.queryForObject("select kind from customer.customers where id = ?", String.class, existing)).isEqualTo("REGULAR");
                assertThat(jdbc.queryForObject("select reseller_id from customer.customers where id = ?", UUID.class, existing)).isNull();
                UUID reseller = customer(jdbc, "Reseller");
                UUID client = customer(jdbc, "Client");
                // Only resellers can be referenced, through the kind that the reference carries.
                assertThatThrownBy(() -> associate(jdbc, client, existing)).isInstanceOf(DataIntegrityViolationException.class);
                jdbc.update("update customer.customers set kind = 'RESELLER' where id = ?", reseller);
                associate(jdbc, client, reseller);
                assertThatThrownBy(() -> jdbc.update("update customer.customers set kind = 'REGULAR' where id = ?", reseller))
                    .isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(() -> jdbc.update("update customer.customers set kind = 'RESELLER' where id = ?", client))
                    .isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(() -> associate(jdbc, reseller, reseller)).isInstanceOf(DataIntegrityViolationException.class);
                // Without the kind, the composite foreign key would not be checked: the CHECK must reject it.
                assertThatThrownBy(() -> jdbc.update("update customer.customers set reseller_id = ?, reseller_kind = null where id = ?", reseller, existing))
                    .isInstanceOf(DataIntegrityViolationException.class);
                assertThatThrownBy(() -> jdbc.update("update customer.customers set reseller_id = null, reseller_kind = 'RESELLER' where id = ?", existing))
                    .isInstanceOf(DataIntegrityViolationException.class);
                assertThat(jdbc.queryForList("""
                    select r.code from identity.role_permissions rp
                    join identity.roles r on r.id = rp.role_id join identity.permissions p on p.id = rp.permission_id
                    where p.code = 'RESELLER_MANAGE'
                    """, String.class)).containsExactly("ADMIN");
            } finally {
                statement.execute("drop database " + database + " with (force)");
            }
        }
    }

    private static UUID customer(JdbcTemplate jdbc, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into customer.customers(id, business_name, created_at) values (?, ?, now())", id, name);
        return id;
    }

    private static void associate(JdbcTemplate jdbc, UUID customer, UUID reseller) {
        jdbc.update("update customer.customers set reseller_id = ?, reseller_kind = 'RESELLER' where id = ?", reseller, customer);
    }
}
