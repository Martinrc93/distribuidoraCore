package com.distribuidora.dashboard;

import com.distribuidora.dashboard.application.ReadQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardSellerOrdersTest {
    private JdbcTemplate jdbc;
    private ReadQueryService service;
    private final UUID seller = UUID.randomUUID();
    private final UUID customer = UUID.randomUUID();
    private final LocalDate day = LocalDate.of(2026, 9, 30);

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        for (String schema : List.of("orders", "sale", "customer", "payment")) jdbc.execute("create schema " + schema);
        jdbc.execute("create table customer.customers(id uuid primary key, business_name varchar)");
        jdbc.execute("create table orders.orders(id uuid primary key, order_number varchar, customer_id uuid, seller_id uuid, status varchar, created_at timestamp with time zone, delivered_at timestamp with time zone)");
        jdbc.execute("create table sale.sales(id uuid primary key, order_id uuid unique, status varchar, total decimal(19,4), paid decimal(19,4))");
        jdbc.execute("create table customer.account_ledger(sale_id uuid, entry_type varchar, amount decimal(19,4))");
        jdbc.execute("create table payment.payments(sale_id uuid, method varchar, amount decimal(19,4))");
        jdbc.update("insert into customer.customers values (?, 'Almacén Norte')", customer);
        service = new ReadQueryService(jdbc);
    }

    @Test
    void filtersBeforePaginationAndCombinesPaymentMethodsWithoutDuplicatingOrders() {
        UUID start = insert("START", seller, "DELIVERED", "2026-09-30T03:00:00Z");
        UUID end = insert("END", seller, "DELIVERED", "2026-10-01T02:59:59.999999Z");
        jdbc.update("insert into payment.payments values (?, 'CASH', 10), (?, 'CASH', 10), (?, 'BANK_TRANSFER', 20)", end, end, end);
        jdbc.update("insert into customer.account_ledger values (?, 'DEBIT', 100), (?, 'CREDIT', 40)", end, end);
        insert("BEFORE", seller, "DELIVERED", "2026-09-30T02:59:59.999999Z");
        insert("AFTER", seller, "DELIVERED", "2026-10-01T03:00:00Z");
        insert("CONFIRMED", seller, "CONFIRMED", "2026-09-30T12:00:00Z");
        insert("CANCELLED", seller, "CANCELLED", "2026-09-30T12:00:00Z");
        insert("OTHER", UUID.randomUUID(), "DELIVERED", "2026-09-30T12:00:00Z");
        insert("UNASSIGNED", null, "DELIVERED", "2026-09-30T12:00:00Z");
        var first = service.dashboardSellerOrders(seller, false, day, day, 0, 1);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.totalPages()).isEqualTo(2);
        var row = first.content().getFirst();
        assertThat(row.get("number")).isEqualTo("END");
        assertThat(row.get("customer")).isEqualTo("Almacén Norte");
        assertThat((BigDecimal) row.get("accountBalance")).isEqualByComparingTo("60");
        assertThat((List<?>) row.get("payments")).hasSize(2);
        var second = service.dashboardSellerOrders(seller, false, day, day, 1, 1);
        assertThat(second.content().getFirst().get("saleId")).isEqualTo(start);
        assertThat((List<?>) second.content().getFirst().get("payments")).isEmpty();
        assertThat(service.dashboardSellerOrders(null, true, day, day, 0, 20).content())
            .extracting(item -> item.get("number")).containsExactly("UNASSIGNED");
    }

    @Test
    void returnsEmptyForNoDeliveredOrdersAndRejectsAmbiguousSelectionAndDates() {
        assertThat(service.dashboardSellerOrders(seller, false, day, day, 0, 20).content()).isEmpty();
        assertThatThrownBy(() -> service.dashboardSellerOrders(null, false, day, day, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.dashboardSellerOrders(seller, true, day, day, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.dashboardSellerOrders(seller, false, day.plusDays(1), day, 0, 20)).isInstanceOf(IllegalArgumentException.class);
    }

    private UUID insert(String number, UUID sellerId, String status, String timestamp) {
        UUID order = UUID.randomUUID();
        UUID sale = UUID.randomUUID();
        Timestamp created = Timestamp.from(Instant.parse(timestamp));
        jdbc.update("insert into orders.orders values (?, ?, ?, ?, ?, ?, ?)", order, number, customer, sellerId, status, created, created);
        jdbc.update("insert into sale.sales values (?, ?, ?, 100, 40)", sale, order, status);
        return sale;
    }
}
