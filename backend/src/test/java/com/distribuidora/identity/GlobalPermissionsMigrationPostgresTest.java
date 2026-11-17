package com.distribuidora.identity;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class GlobalPermissionsMigrationPostgresTest {
    @Test
    void removesOnlyGlobalGrantsFromAssignedRolesAndInvalidatesAffectedSessions() {
        String source = System.getenv("POSTGRES_TEST_URL");
        String username = System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "distribuidora");
        String password = System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "distribuidora");
        String database = "global_perms_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(source, username, password));
        admin.execute("create database " + database);
        try {
            String url = source.substring(0, source.lastIndexOf('/') + 1) + database;
            Flyway.configure().dataSource(url, username, password).target("44").load().migrate();
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
            UUID sellerRole = jdbc.queryForObject("select id from identity.roles where code = 'SELLER'", UUID.class);
            UUID adminRole = jdbc.queryForObject("select id from identity.roles where code = 'ADMIN'", UUID.class);
            jdbc.update("""
                insert into identity.role_permissions(role_id, permission_id)
                select ?, id from identity.permissions where code in ('STOCK_ADJUST', 'USER_MANAGE')
                """, sellerRole);
            UUID affected = createSession(jdbc, sellerRole);
            UUID unaffected = createSession(jdbc, adminRole);

            var flyway = Flyway.configure().dataSource(url, username, password).target("45").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            String grants = """
                select p.code from identity.role_permissions rp
                join identity.permissions p on p.id = rp.permission_id where rp.role_id = ?
                """;
            assertThat(jdbc.queryForList(grants, String.class, sellerRole))
                .contains("ORDER_CREATE").doesNotContain("STOCK_ADJUST", "USER_MANAGE");
            assertThat(jdbc.queryForList(grants, String.class, adminRole))
                .contains("ADMIN_ALL", "STOCK_ADJUST", "USER_MANAGE");
            assertSession(jdbc, affected, 8, true);
            assertSession(jdbc, unaffected, 7, false);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertSession(jdbc, affected, 8, true);
        } finally {
            admin.execute("drop database " + database);
        }
    }

    private UUID createSession(JdbcTemplate jdbc, UUID role) {
        UUID user = UUID.randomUUID();
        jdbc.update("""
            insert into identity.users(id, email, password_hash, status, created_at, updated_at, version)
            values (?, ?, '!fixture!', 'ACTIVE', now(), now(), 7)
            """, user, user + "@example.test");
        jdbc.update("insert into identity.user_roles(user_id, role_id) values (?, ?)", user, role);
        jdbc.update("""
            insert into identity.refresh_tokens(id, user_id, token_hash, expires_at, created_at)
            values (?, ?, ?, now() + interval '1 hour', now())
            """, UUID.randomUUID(), user, "fixture-" + user);
        return user;
    }

    private void assertSession(JdbcTemplate jdbc, UUID user, long version, boolean revoked) {
        assertThat(jdbc.queryForObject("select version from identity.users where id = ?", Long.class, user))
            .isEqualTo(version);
        assertThat(jdbc.queryForObject("select revoked_at is not null from identity.refresh_tokens where user_id = ?", Boolean.class, user))
            .isEqualTo(revoked);
    }
}
