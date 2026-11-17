package com.distribuidora.identity;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class OrderActionPermissionsMigrationPostgresTest {
    @Test
    void upgradesExistingRolesWithoutBroadeningTheirScopeAndRevokesAffectedSessionsOnce() {
        String source = System.getenv("POSTGRES_TEST_URL");
        String username = System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "distribuidora");
        String password = System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "distribuidora");
        String database = "phase54_migration_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(source, username, password));
        admin.execute("create database " + database);
        try {
            String url = source.substring(0, source.lastIndexOf('/') + 1) + database;
            Flyway.configure().dataSource(url, username, password).target("42").load().migrate();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
            UUID role = UUID.randomUUID();
            jdbc.update("insert into identity.roles(id, code, description) values (?, 'CUSTOM_ORDER', 'Asignado')", role);
            jdbc.update("insert into identity.role_permissions select ?, id from identity.permissions where code = 'ORDER_CREATE'", role);
            UUID affected = UUID.randomUUID(); UUID unaffected = UUID.randomUUID();
            for (UUID user : new UUID[]{affected, unaffected}) {
                jdbc.update("insert into identity.users(id, email, password_hash, status, created_at, updated_at, version) values (?, ?, '!fixture!', 'ACTIVE', now(), now(), 7)", user, user + "@example.test");
                jdbc.update("insert into identity.refresh_tokens(id, user_id, token_hash, expires_at, created_at) values (?, ?, ?, now() + interval '1 hour', now())", UUID.randomUUID(), user, "fixture-" + user);
            }
            jdbc.update("insert into identity.user_roles(user_id, role_id) values (?, ?)", affected, role);
            var flyway = Flyway.configure().dataSource(url, username, password).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(jdbc.queryForList("select p.code from identity.role_permissions rp join identity.permissions p on p.id = rp.permission_id where rp.role_id = ?", String.class, role))
                .containsExactlyInAnyOrder("ORDER_CREATE", "ORDER_DOCUMENT_EXPORT", "ORDER_NOTIFY");
            assertThat(jdbc.queryForObject("select version from identity.users where id = ?", Long.class, affected)).isEqualTo(8L);
            assertThat(jdbc.queryForObject("select revoked_at is not null from identity.refresh_tokens where user_id = ?", Boolean.class, affected)).isTrue();
            assertThat(jdbc.queryForObject("select version from identity.users where id = ?", Long.class, unaffected)).isEqualTo(7L);
            assertThat(jdbc.queryForObject("select revoked_at is null from identity.refresh_tokens where user_id = ?", Boolean.class, unaffected)).isTrue();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("select version from identity.users where id = ?", Long.class, affected)).isEqualTo(8L);
            assertThat(jdbc.queryForObject("select count(*) from identity.role_permissions rp join identity.roles r on r.id = rp.role_id join identity.permissions p on p.id = rp.permission_id where r.code = 'SELLER' and p.code in ('ORDER_EDIT', 'ORDER_CANCEL', 'ORDER_REACTIVATE', 'SALE_RETURN', 'ORDER_PRICE_OVERRIDE', 'ORDER_DISCOUNT')", Long.class)).isZero();
        } finally {
            admin.execute("drop database " + database);
        }
    }
}
