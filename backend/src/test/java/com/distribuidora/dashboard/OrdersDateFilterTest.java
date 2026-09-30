package com.distribuidora.dashboard;

import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrdersDateFilterTest {
    private JdbcTemplate jdbc;
    private ReadQueryService service;
    private final UUID sellerId = UUID.randomUUID();
    private final UUID otherSellerId = UUID.randomUUID();
    private final UUID customerId = UUID.randomUUID();
    private final LocalDate day = LocalDate.of(2026, 9, 30);

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create schema orders");
        jdbc.execute("create schema customer");
        jdbc.execute("create schema seller");
        jdbc.execute("create table customer.customers(id uuid primary key, business_name varchar, seller_id uuid)");
        jdbc.execute("create table seller.seller_profiles(id uuid primary key, display_name varchar)");
        jdbc.execute("create table orders.orders(id uuid primary key, order_number varchar, customer_id uuid, seller_id uuid, total decimal, status varchar, created_at timestamp with time zone)");
        jdbc.update("insert into customer.customers values (?, 'Almacén Norte', ?)", customerId, sellerId);
        service = new ReadQueryService(jdbc);
    }

    @Test
    void includesTheWholeArgentineDayAndFiltersBeforePaginationAndCounting() {
        insert("BEFORE", "2026-09-30T02:59:59.999999Z", sellerId, "CONFIRMED");
        insert("START", "2026-09-30T03:00:00Z", sellerId, "CONFIRMED");
        insert("END", "2026-10-01T02:59:59.999999Z", sellerId, "CONFIRMED");
        insert("AFTER", "2026-10-01T03:00:00Z", sellerId, "CONFIRMED");
        insert("CANCELLED", "2026-09-30T12:00:00Z", sellerId, "CANCELLED");

        var first = service.orders(0, 1, "Norte", "CONFIRMED", day, day);
        var second = service.orders(1, 1, "Norte", "CONFIRMED", day, day);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.content()).extracting(row -> row.get("NUMBER")).containsExactly("END");
        assertThat(second.content()).extracting(row -> row.get("NUMBER")).containsExactly("START");
        assertThat(service.orders(0, 20, "missing", "", day, day).totalElements()).isZero();
        assertThat(service.orders(0, 20, "", "CANCELLED", day, day).totalElements()).isEqualTo(1);
    }

    @Test
    void acceptsOpenBoundariesAndKeepsSellerVisibility() {
        insert("BEFORE", "2026-09-29T12:00:00Z", sellerId, "CONFIRMED");
        insert("OWN", "2026-09-30T12:00:00Z", sellerId, "CONFIRMED");
        insert("FALLBACK", "2026-09-30T12:01:00Z", null, "DELIVERED");
        insert("OTHER", "2026-09-30T12:02:00Z", otherSellerId, "CONFIRMED");
        insert("AFTER", "2026-10-01T12:00:00Z", sellerId, "CONFIRMED");
        assertThat(service.orders(0, 20, "", "", null, day).totalElements()).isEqualTo(4);
        assertThat(service.orders(0, 20, "", "", day, null).totalElements()).isEqualTo(4);
        assertThat(service.orders(0, 20, "", "").totalElements()).isEqualTo(5);

        CurrentUserAccess access = mock(CurrentUserAccess.class);
        when(access.isAdmin()).thenReturn(false);
        when(access.requireSellerProfile()).thenReturn(sellerId);
        var scoped = new ReadQueryService(jdbc, access).orders(0, 20, "", "", day, day);
        assertThat(scoped.totalElements()).isEqualTo(2);
        assertThat(scoped.content()).extracting(row -> row.get("NUMBER")).containsExactly("FALLBACK", "OWN");
    }

    @Test
    void rejectsReversedRanges() {
        assertThatThrownBy(() -> service.orders(0, 20, "", "", day.plusDays(1), day))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fecha mínima");
    }

    private void insert(String number, String timestamp, UUID seller, String status) {
        jdbc.update("insert into orders.orders values (?, ?, ?, ?, 10, ?, ?)",
            UUID.randomUUID(), number, customerId, seller, status, Timestamp.from(Instant.parse(timestamp)));
    }
}
