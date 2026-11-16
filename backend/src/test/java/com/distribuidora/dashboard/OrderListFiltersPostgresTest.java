package com.distribuidora.dashboard;

import com.distribuidora.dashboard.application.ReadQueryService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class OrderListFiltersPostgresTest {
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(System.getenv("POSTGRES_TEST_URL"),
            System.getenv().getOrDefault("POSTGRES_TEST_USERNAME", "distribuidora"),
            System.getenv().getOrDefault("POSTGRES_TEST_PASSWORD", "distribuidora"));
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @Test
    void filtersByCreatorAndPaidUndeliveredOrders() {
        transactions.executeWithoutResult(status -> {
            try {
                UUID customer = UUID.randomUUID();
                String tag = "FLT" + customer.toString().substring(0, 8);
                jdbc.update("insert into customer.customers(id, business_name, tax_id, balance, status, created_at) values (?, ?, ?, 0, 'ACTIVE', now())", customer, tag, tag);
                UUID adminPaid = order(customer, tag + "-A", true, "CONFIRMED", "100", "100");
                UUID sellerPartial = order(customer, tag + "-B", false, "CONFIRMED", "100", "40");
                UUID sellerDelivered = order(customer, tag + "-C", false, "DELIVERED", "50", "50");
                order(customer, tag + "-D", null, "CONFIRMED", "10", "0");
                var queries = new ReadQueryService(jdbc);

                assertThat(ids(queries.orders(0, 100, tag, "", null, null, "ADMIN", false).content())).containsExactly(adminPaid);
                assertThat(ids(queries.orders(0, 100, tag, "", null, null, "SELLER", false).content())).containsExactlyInAnyOrder(sellerPartial, sellerDelivered);
                var paid = queries.orders(0, 100, tag, "", null, null, null, true);
                assertThat(ids(paid.content())).containsExactly(adminPaid);
                assertThat(paid.totalElements()).isEqualTo(1);
                assertThat(paid.content().getFirst()).containsEntry("createdByAdmin", true);
                assertThat((BigDecimal) paid.content().getFirst().get("paid")).isEqualByComparingTo("100");
                assertThat(queries.orders(0, 100, tag, "", null, null, null, false).totalElements()).isEqualTo(4);
                assertThatThrownBy(() -> queries.orders(0, 20, "", "", null, null, "OTHER", false)).isInstanceOf(IllegalArgumentException.class);
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    private java.util.List<Object> ids(java.util.List<Map<String, Object>> rows) {
        return rows.stream().map(row -> row.get("id")).toList();
    }

    private UUID order(UUID customer, String number, Boolean admin, String status, String total, String paid) {
        UUID order = UUID.randomUUID();
        jdbc.update("insert into orders.orders(id, order_number, customer_id, status, subtotal, discount, total, created_at, created_by_admin) values (?, ?, ?, ?, ?, 0, ?, now(), ?)",
            order, number, customer, status, new BigDecimal(total), new BigDecimal(total), admin);
        jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, created_at) values (?, ?, ?, ?, ?, ?, ?, now())",
            UUID.randomUUID(), "S-" + number, order, customer, status, new BigDecimal(total), new BigDecimal(paid));
        return order;
    }
}
