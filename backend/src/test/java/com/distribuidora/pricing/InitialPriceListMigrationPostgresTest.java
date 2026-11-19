package com.distribuidora.pricing;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.pricing.application.PricingCommandService;
import com.distribuidora.pricing.infrastructure.InitialPriceListMigrationCallback;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class InitialPriceListMigrationPostgresTest {
    @Test
    void freshDatabaseStartsWithGeneralAndAllowsMoreThanTenLists() throws SQLException {
        withDatabase(source -> {
            migrate(source);
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForList("select code, is_default, status from catalog.price_lists"))
                .singleElement().satisfies(list -> assertThat(list)
                    .containsEntry("code", "GENERAL").containsEntry("is_default", true).containsEntry("status", "ACTIVE"));

            var pricing = new PricingCommandService(jdbc, mock(AuditService.class));
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), null));
            try {
                for (int number = 1; number <= 10; number++) {
                    pricing.createList("CUSTOM_" + number, "Custom list " + number);
                }
                assertThat(jdbc.queryForObject("select count(*) from catalog.price_lists", Integer.class)).isEqualTo(11);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    @Test
    void upgradingAnExistingDatabaseKeepsPreviouslySeededLists() throws SQLException {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).target("9").load().migrate();
            var jdbc = new JdbcTemplate(source);
            jdbc.update("update catalog.price_lists set name = 'Wholesale' where code = 'LISTA_2'");
            var lists = jdbc.queryForList("select * from catalog.price_lists order by code");
            assertThat(lists).hasSize(10);

            migrate(source);

            assertThat(jdbc.queryForList("select * from catalog.price_lists order by code")).isEqualTo(lists);
        });
    }

    private void migrate(DriverManagerDataSource source) {
        Flyway.configure().dataSource(source).callbacks(new InitialPriceListMigrationCallback()).load().migrate();
    }

    private void withDatabase(Consumer<DriverManagerDataSource> test) throws SQLException {
        String url = System.getenv("POSTGRES_TEST_URL");
        String user = System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "distribuidora");
        String password = System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "distribuidora");
        String database = "pricing_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
            statement.execute("create database " + database);
            try {
                test.accept(new DriverManagerDataSource(url.substring(0, url.lastIndexOf('/') + 1) + database, user, password));
            } finally {
                statement.execute("drop database " + database + " with (force)");
            }
        }
    }
}
