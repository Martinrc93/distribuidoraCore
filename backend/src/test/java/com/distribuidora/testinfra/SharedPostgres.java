package com.distribuidora.testinfra;

import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;

public final class SharedPostgres {
    private SharedPostgres() { }

    private static final class Instance {
        private static final PostgreSQLContainer<?> POSTGRES = start();

        private static PostgreSQLContainer<?> start() {
            var postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
            return postgres;
        }
    }

    public static Database databaseFor(Class<?> testClass) {
        String name = testClass.getSimpleName().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Invalid test database name: " + name);
        }
        var container = Instance.POSTGRES;
        String jdbcUrl = container.getJdbcUrl();
        try (var connection = DriverManager.getConnection(jdbcUrl, container.getUsername(), container.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + name);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to create PostgreSQL test database " + name, exception);
        }
        String databaseUrl = jdbcUrl.substring(0, jdbcUrl.lastIndexOf('/') + 1) + name;
        return new Database(databaseUrl, container.getUsername(), container.getPassword());
    }

    public record Database(String jdbcUrl, String username, String password) {
        public String getJdbcUrl() { return jdbcUrl; }
        public String getUsername() { return username; }
        public String getPassword() { return password; }
    }
}
