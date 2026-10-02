package com.distribuidora.dashboard;

import com.distribuidora.dashboard.application.DashboardAnalyticsService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class DashboardAnalyticsPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static DashboardAnalyticsService service;

    @BeforeAll
    static void initialize() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        service = new DashboardAnalyticsService(jdbc, Clock.fixed(Instant.parse("2026-10-02T13:00:00Z"), ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> result, String key) {
        return (Map<String, Object>) result.get(key);
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> result, String key) {
        return (List<Map<String, Object>>) result.get(key);
    }
    private static void amount(Map<String, Object> result, String key, String expected) {
        assertThat(new BigDecimal(result.get(key).toString())).isEqualByComparingTo(expected);
    }

    @Test
    void usesBusinessDatesPaymentDatesCurrentPrioritiesAndReturnAwareCoverage() {
        UUID customer = UUID.randomUUID();
        UUID product = product("Selling product", "ACTIVE", 15);
        UUID second = product("Second product", "ACTIVE", 10);
        product("Negative product", "ACTIVE", -2);
        product("Missing balance", "ACTIVE", null);
        product("Inactive product", "INACTIVE", -5);
        UUID noSales = product("No sales product", "ACTIVE", 7);
        jdbc.update("insert into customer.customers(id, business_name, tax_id, balance, created_at) values (?, 'Analytics customer', ?, 750, now())", customer, customer.toString());
        UUID prior = sale(customer, "2026-09-01T02:59:59Z", "DELIVERED", 100, 100, 0);
        UUID first = sale(customer, "2026-09-01T03:00:00Z", "CONFIRMED", 300, 100, 25);
        UUID last = sale(customer, "2026-10-01T02:59:59Z", "DELIVERED", 500, 50, 0);
        UUID after = sale(customer, "2026-10-01T03:00:00Z", "CONFIRMED", 100, 0, 0);
        sale(customer, "2026-09-15T12:00:00Z", "CANCELLED", 900, 0, 0);
        item(first, product, 4, 400);
        UUID returnedLine = item(last, product, 10, 500);
        item(after, second, 2, 100);
        payment(prior, customer, "2026-09-05T15:00:00Z", 100, "CASH");
        payment(last, customer, "2026-10-01T02:59:59Z", 50, "BANK_TRANSFER");
        payment(first, customer, "2026-10-01T15:00:00Z", 100, "CASH");
        ledger(first, customer, 200);
        ledger(last, customer, 450);
        ledger(after, customer, 100);
        UUID actor = UUID.randomUUID();
        jdbc.update("insert into identity.users(id, email, password_hash, status, created_at, updated_at) values (?, ?, 'test', 'ACTIVE', now(), now())", actor, actor + "@example.test");
        UUID returned = UUID.randomUUID();
        jdbc.update("insert into sale.returns(id, sale_id, returned_by, reason, created_at) values (?, ?, ?, 'Test return', now())", returned, last, actor);
        jdbc.update("insert into sale.return_items(id, return_id, sale_id, sale_item_id, product_id, quantity) values (?, ?, ?, ?, ?, 2)", UUID.randomUUID(), returned, last, returnedLine, product);
        UUID pendingOrder = jdbc.queryForObject("select order_id from sale.sales where id = ?", UUID.class, first);
        jdbc.update("insert into orders.delivery_attempts(id, order_id, attempt_number, result, observation, attempted_by, attempted_at) values (?, ?, 1, 'FAILED', 'No answer', ?, now())", UUID.randomUUID(), pendingOrder, actor);

        Map<String, Object> report = service.report(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        amount(object(report, "sales"), "amount", "800");
        amount(object(report, "sales"), "orders", "2");
        amount(object(report, "previousSales"), "amount", "100");
        amount(object(report, "collections"), "amount", "150");
        amount(object(report, "collections"), "cash", "100");
        amount(object(report, "collections"), "transfer", "50");
        amount(object(report, "current"), "debt", "750");
        amount(object(report, "current"), "pendingOrders", "2");
        amount(object(report, "current"), "pendingAmount", "400");
        amount(object(report, "current"), "failedDeliveries", "1");
        amount(object(report, "current"), "stockAlertCount", "2");
        amount(object(report, "debtAging"), "days31to60", "200");
        amount(object(report, "debtAging"), "days0to30", "550");
        assertThat(rows(report, "trend")).hasSize(30);
        amount(rows(report, "trend").getFirst(), "amount", "300");
        amount(rows(report, "trend").get(1), "amount", "0");
        amount(rows(report, "trend").getLast(), "amount", "500");
        amount(rows(report, "topProducts").getFirst(), "amount", "800");
        amount(rows(report, "topProducts").getFirst(), "units", "14");
        amount(rows(report, "sellers").getFirst(), "amount", "800");
        assertThat(rows(report, "sellers").getFirst().get("name")).isEqualTo("Sin asignar");
        Map<String, Object> coverage = rows(report, "stockCoverage").stream().filter(row -> product.equals(row.get("id"))).findFirst().orElseThrow();
        amount(coverage, "days", "56.3");
        assertThat(rows(report, "stockCoverage").stream().filter(row -> noSales.equals(row.get("id"))).findFirst().orElseThrow().get("days")).isNull();
        assertThat(rows(report, "recentOrders")).hasSize(3);
    }

    @Test
    void comparesPartialDaysAtTheSameTimeAndRejectsInvalidRangesBeforeQuerying() {
        var mockJdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.when(mockJdbc.queryForMap(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(Map.of());
        var fixed = new DashboardAnalyticsService(mockJdbc, Clock.fixed(Instant.parse("2026-10-02T13:00:00Z"), ZoneOffset.UTC));
        fixed.report(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 2));
        org.mockito.Mockito.verify(mockJdbc, org.mockito.Mockito.atLeastOnce()).queryForMap(
            org.mockito.ArgumentMatchers.contains("from sale.sales where status"),
            org.mockito.ArgumentMatchers.eq(Timestamp.from(Instant.parse("2026-10-01T03:00:00Z"))),
            org.mockito.ArgumentMatchers.eq(Timestamp.from(Instant.parse("2026-10-01T13:00:00Z"))));
        assertThatThrownBy(() -> fixed.report(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 2))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixed.report(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 3))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fixed.report(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 2))).isInstanceOf(IllegalArgumentException.class);
    }

    private static UUID product(String name, String status, Integer stock) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products(id, name, category, presentation, cost, status, created_at) values (?, ?, 'Test', 'Unit', 1, ?, now())", id, name, status);
        if (stock != null) jdbc.update("insert into inventory.inventory_balances(product_id, quantity, updated_at) values (?, ?, now())", id, stock);
        return id;
    }
    private static UUID sale(UUID customer, String date, String status, int total, int paid, int discount) {
        UUID order = UUID.randomUUID();
        UUID sale = UUID.randomUUID();
        jdbc.update("insert into orders.orders(id, order_number, customer_id, status, subtotal, total, created_at) values (?, ?, ?, ?, ?, ?, ?)", order, order.toString().substring(0, 20), customer, status, total, total, Timestamp.from(Instant.parse(date)));
        jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, status, total, paid, order_discount_percent, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)", sale, sale.toString().substring(0, 20), order, customer, status, total, paid, discount, Timestamp.from(Instant.parse(date)));
        return sale;
    }
    private static UUID item(UUID sale, UUID product, int quantity, int lineTotal) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into sale.sale_items(id, sale_id, product_id, product_name, quantity, unit_price, line_total, price_list_code, line_discount_percent) values (?, ?, ?, 'Selling product', ?, ?, ?, 'GENERAL', 0)", id, sale, product, quantity, lineTotal / quantity, lineTotal);
        return id;
    }
    private static void payment(UUID sale, UUID customer, String date, int amount, String method) {
        jdbc.update("insert into payment.payments(id, sale_id, customer_id, amount, method, created_at) values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), sale, customer, amount, method, Timestamp.from(Instant.parse(date)));
    }
    private static void ledger(UUID sale, UUID customer, int amount) {
        jdbc.update("insert into customer.account_ledger(id, sale_id, customer_id, entry_type, amount, created_at) values (?, ?, ?, 'DEBIT', ?, now())", UUID.randomUUID(), sale, customer, amount);
    }
}
