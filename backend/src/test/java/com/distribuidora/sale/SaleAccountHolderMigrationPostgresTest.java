package com.distribuidora.sale;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.DriverManager;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class SaleAccountHolderMigrationPostgresTest {
    private static final String URL = System.getenv("POSTGRES_TEST_URL");
    private static final String USER = System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "distribuidora");
    private static final String PASSWORD = System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "distribuidora");

    @Test
    void backfillsTheRecipientAsAccountAndKeepsEntriesAndPaymentsOnThatAccount() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).target("50").load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID owner = customer(jdbc, "Owner");
            UUID other = customer(jdbc, "Other");
            UUID sale = sale(jdbc, owner);
            jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'DEBIT', 10, now())",
                UUID.randomUUID(), owner, sale);
            jdbc.update("insert into payment.payments(id, sale_id, customer_id, amount, method, created_at) values (?, ?, ?, 4, 'CASH', now())",
                UUID.randomUUID(), sale, owner);

            Flyway.configure().dataSource(source).load().migrate();

            assertThat(jdbc.queryForObject("select account_customer_id from sale.sales where id = ?", UUID.class, sale)).isEqualTo(owner);
            assertThat(jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                where table_schema = 'sale' and table_name = 'sales' and column_name = 'account_customer_id'
                """, String.class)).isEqualTo("NO");
            assertThatThrownBy(() -> jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'CREDIT', 1, now())",
                UUID.randomUUID(), other, sale)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("insert into payment.payments(id, sale_id, customer_id, amount, method, created_at) values (?, ?, ?, 1, 'CASH', now())",
                UUID.randomUUID(), sale, other)).isInstanceOf(DataIntegrityViolationException.class);
            // An account with entries cannot be swapped: the history stays on the account that owed the sale.
            assertThatThrownBy(() -> jdbc.update("update sale.sales set account_customer_id = ? where id = ?", other, sale))
                .isInstanceOf(DataIntegrityViolationException.class);
            UUID order = order(jdbc, other);
            assertThatThrownBy(() -> jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at) values (?, 'ACC-NO-ACCOUNT', ?, ?, 'CONFIRMED', 10, 0, now())",
                UUID.randomUUID(), order, other)).isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'CREDIT', 6, now())",
                UUID.randomUUID(), owner, sale);
            assertThat(jdbc.queryForObject("select count(*) from customer.account_ledger where sale_id = ?", Integer.class, sale)).isEqualTo(2);
        });
    }

    @Test
    void refusesToMigrateLedgerEntriesOfAnotherCustomer() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).target("50").load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID sale = sale(jdbc, customer(jdbc, "Owner"));
            jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'DEBIT', 10, now())",
                UUID.randomUUID(), customer(jdbc, "Other"), sale);

            assertThatThrownBy(() -> Flyway.configure().dataSource(source).load().migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("Account ledger entries must belong to the customer of their sale");
            assertThat(jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'sale' and table_name = 'sales' and column_name = 'account_customer_id'
                """, Integer.class)).isZero();
        });
    }

    @Test
    void refusesToMigratePaymentsOfAnotherCustomer() throws Exception {
        withDatabase(source -> {
            Flyway.configure().dataSource(source).target("50").load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID sale = sale(jdbc, customer(jdbc, "Owner"));
            jdbc.update("insert into payment.payments(id, sale_id, customer_id, amount, method, created_at) values (?, ?, ?, 4, 'CASH', now())",
                UUID.randomUUID(), sale, customer(jdbc, "Other"));

            assertThatThrownBy(() -> Flyway.configure().dataSource(source).load().migrate())
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("Payments must belong to the customer of their sale");
        });
    }

    private static UUID customer(JdbcTemplate jdbc, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into customer.customers(id, business_name, created_at) values (?, ?, now())", id, name);
        return id;
    }

    private static UUID order(JdbcTemplate jdbc, UUID customer) {
        UUID order = UUID.randomUUID();
        jdbc.update("insert into orders.orders(id, order_number, customer_id, status, subtotal, discount, total, created_at) values (?, ?, ?, 'CONFIRMED', 10, 0, 10, now())",
            order, "ACC-" + order.toString().substring(0, 20), customer);
        return order;
    }

    /** A sale written before V51, without an account column. */
    private static UUID sale(JdbcTemplate jdbc, UUID customer) {
        UUID sale = UUID.randomUUID();
        jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at) values (?, ?, ?, ?, 'CONFIRMED', 10, 0, now())",
            sale, "ACC-" + sale.toString().substring(0, 20), order(jdbc, customer), customer);
        return sale;
    }

    private static void withDatabase(Consumer<DriverManagerDataSource> test) throws Exception {
        String database = "sale_account_" + UUID.randomUUID().toString().replace("-", "");
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
