package com.distribuidora.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DiscountRuleRemovalMigrationTest {
    @Test
    void removesRulesAndReferencesWithoutChangingRecordedAmounts() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        for (String schema : new String[]{"catalog", "orders", "sale", "customer", "payment"}) {
            jdbc.execute("create schema " + schema);
        }
        jdbc.execute("create table catalog.commercial_discount_rules(id uuid primary key)");
        for (String table : new String[]{"orders.orders", "sale.sales"}) {
            jdbc.execute("create table " + table + "(id uuid primary key, total numeric(19,4), "
                + "order_discount_percent numeric(19,4), order_discount_rule_id uuid "
                + "references catalog.commercial_discount_rules(id))");
        }
        for (String table : new String[]{"orders.order_items", "sale.sale_items"}) {
            jdbc.execute("create table " + table + "(id uuid primary key, line_total numeric(19,4), "
                + "line_discount_percent numeric(19,4), discount_rule_id uuid "
                + "references catalog.commercial_discount_rules(id))");
        }
        jdbc.execute("create table customer.customers(balance numeric(19,4))");
        jdbc.execute("create table customer.account_ledger(amount numeric(19,4))");
        jdbc.execute("create table payment.payments(amount numeric(19,4))");
        UUID rule = UUID.randomUUID();
        jdbc.update("insert into catalog.commercial_discount_rules values (?)", rule);
        for (String table : new String[]{"orders.orders", "sale.sales"}) {
            jdbc.update("insert into " + table + " values (?, 85.5, 5, ?)", UUID.randomUUID(), rule);
        }
        for (String table : new String[]{"orders.order_items", "sale.sale_items"}) {
            jdbc.update("insert into " + table + " values (?, 90, 10, ?)", UUID.randomUUID(), rule);
        }
        jdbc.update("insert into customer.customers values (65.5)");
        jdbc.update("insert into customer.account_ledger values (65.5)");
        jdbc.update("insert into payment.payments values (20)");

        new ResourceDatabasePopulator(new ClassPathResource(
            "db/migration/V32__remove_commercial_discount_rules.sql")).execute(dataSource);

        for (String table : new String[]{"orders.orders", "sale.sales"}) {
            assertThat(jdbc.queryForObject("select total from " + table, BigDecimal.class)).isEqualByComparingTo("85.5");
            assertThat(jdbc.queryForObject("select order_discount_percent from " + table, BigDecimal.class)).isEqualByComparingTo("5");
        }
        for (String table : new String[]{"orders.order_items", "sale.sale_items"}) {
            assertThat(jdbc.queryForObject("select line_total from " + table, BigDecimal.class)).isEqualByComparingTo("90");
            assertThat(jdbc.queryForObject("select line_discount_percent from " + table, BigDecimal.class)).isEqualByComparingTo("10");
        }
        assertThat(jdbc.queryForObject("select balance from customer.customers", BigDecimal.class)).isEqualByComparingTo("65.5");
        assertThat(jdbc.queryForObject("select amount from customer.account_ledger", BigDecimal.class)).isEqualByComparingTo("65.5");
        assertThat(jdbc.queryForObject("select amount from payment.payments", BigDecimal.class)).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'COMMERCIAL_DISCOUNT_RULES'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where column_name in ('DISCOUNT_RULE_ID', 'ORDER_DISCOUNT_RULE_ID')", Integer.class)).isZero();
    }
}
