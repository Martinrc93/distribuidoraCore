package com.distribuidora.dashboard;

import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.web.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;
import java.util.UUID;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SalesFiltersTest {
    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
        "jdbc:h2:mem:sales-filters;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", ""));
    private final ReadQueryService service = new ReadQueryService(jdbc);
    private UUID ana;
    private UUID lucia;
    private UUID north;
    private UUID south;

    @BeforeEach
    void setUp() {
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE SCHEMA customer");
        jdbc.execute("CREATE SCHEMA seller");
        jdbc.execute("CREATE SCHEMA orders");
        jdbc.execute("CREATE SCHEMA sale");
        jdbc.execute("CREATE TABLE seller.seller_profiles (id UUID PRIMARY KEY, display_name VARCHAR(120))");
        jdbc.execute("CREATE TABLE customer.customers (id UUID PRIMARY KEY, business_name VARCHAR(120), seller_id UUID)");
        jdbc.execute("CREATE TABLE orders.orders (id UUID PRIMARY KEY, seller_id UUID)");
        jdbc.execute("CREATE TABLE sale.sales (id UUID PRIMARY KEY, sale_number VARCHAR(40), order_id UUID, customer_id UUID, total NUMERIC(19,4), paid NUMERIC(19,4), status VARCHAR(20), created_at TIMESTAMP WITH TIME ZONE)");
        ana = UUID.randomUUID();
        lucia = UUID.randomUUID();
        north = UUID.randomUUID();
        south = UUID.randomUUID();
        jdbc.update("INSERT INTO seller.seller_profiles VALUES (?, 'Ana'), (?, 'Lucía')", ana, lucia);
        jdbc.update("INSERT INTO customer.customers VALUES (?, 'Almacén Norte', ?), (?, 'Mercado Sur', ?)", north, ana, south, lucia);
        sale("SAL-001", north, ana, 25, "CONFIRMED");
        sale("SAL-002", north, lucia, 0, "DELIVERED");
        sale("SAL-003", south, lucia, 100, "DELIVERED");
        sale("SAL-004", north, ana, 0, "CANCELLED");
        sale("SAL-005", south, null, 25, "CONFIRMED");
    }

    @Test
    void searchesOnlySaleNumbersAndFiltersCustomerSeparately() {
        assertThat(service.sales(0, 20, "Norte").totalElements()).isZero();
        assertThat(service.sales(0, 20, "  sal-001  ").content()).extracting(row -> row.get("number")).containsExactly("SAL-001");
        assertThat(service.sales(0, 20, "", north, null, false).content())
            .extracting(row -> row.get("number")).containsExactlyInAnyOrder("SAL-001", "SAL-002", "SAL-004");
    }

    @Test
    void filtersByOrderSellerWithCustomerFallback() {
        assertThat(service.sales(0, 20, "", null, lucia, false).content())
            .extracting(row -> row.get("number")).containsExactlyInAnyOrder("SAL-002", "SAL-003", "SAL-005");
        assertThat(service.sales(0, 20, "", north, ana, false).content())
            .extracting(row -> row.get("number")).containsExactlyInAnyOrder("SAL-001", "SAL-004");
    }

    @Test
    void pendingBalanceIncludesUnpaidAndPartialPaymentsButExcludesPaidAndCancelledSales() {
        assertThat(service.sales(0, 20, "", null, null, true).content())
            .extracting(row -> row.get("number")).containsExactlyInAnyOrder("SAL-001", "SAL-002", "SAL-005");
        assertThat(service.sales(0, 20, "SAL-00", north, lucia, true).content())
            .extracting(row -> row.get("number")).containsExactly("SAL-002");
    }

    @Test
    void appliesCombinedFiltersBeforePaginationAndCounts() {
        PageResponse<Map<String, Object>> first = service.sales(0, 1, "", north, null, true);
        PageResponse<Map<String, Object>> second = service.sales(1, 1, "", north, null, true);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.content()).hasSize(1);
        assertThat(second.content()).hasSize(1).doesNotContainAnyElementsOf(first.content());
        assertThat(service.sales(0, 20, "SAL-003", north, null, true).content()).isEmpty();
    }

    @Test
    void sellerFiltersCannotExpandTheAuthenticatedSellersScope() {
        CurrentUserAccess access = mock(CurrentUserAccess.class);
        when(access.isAdmin()).thenReturn(false);
        when(access.requireSellerProfile()).thenReturn(ana);
        ReadQueryService scoped = new ReadQueryService(jdbc, access);
        assertThat(scoped.sales(0, 20, "", null, lucia, true).totalElements()).isZero();
        assertThat(scoped.sales(0, 20, "", null, null, true).content())
            .extracting(row -> row.get("number")).containsExactly("SAL-001");
        when(access.requireSellerProfile()).thenReturn(lucia);
        assertThat(scoped.sales(0, 20, "", null, lucia, true).content())
            .extracting(row -> row.get("number")).containsExactlyInAnyOrder("SAL-002", "SAL-005");
    }

    @Test
    void keepsUnassignedSalesVisibleWithoutASellerFilter() {
        UUID unassigned = UUID.randomUUID();
        jdbc.update("INSERT INTO customer.customers VALUES (?, 'Unassigned', null)", unassigned);
        sale("SAL-006", unassigned, null, 0, "CONFIRMED");
        assertThat(service.sales(0, 20, "SAL-006", null, null, true).totalElements()).isEqualTo(1);
        assertThat(service.sales(0, 20, "SAL-006", null, ana, true).totalElements()).isZero();
    }

    @Test
    void dropdownIdsDistinguishCustomersAndSellersWithTheSameName() {
        UUID duplicateCustomer = UUID.randomUUID();
        UUID duplicateSeller = UUID.randomUUID();
        jdbc.update("INSERT INTO customer.customers VALUES (?, 'Almacén Norte', ?)", duplicateCustomer, duplicateSeller);
        jdbc.update("INSERT INTO seller.seller_profiles VALUES (?, 'Ana')", duplicateSeller);
        sale("SAL-DUPLICATE", duplicateCustomer, duplicateSeller, 0, "CONFIRMED");
        assertThat(service.sales(0, 20, "", duplicateCustomer, duplicateSeller, true).content())
            .extracting(row -> row.get("number")).containsExactly("SAL-DUPLICATE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void providesDistinctDropdownOptionsWithinSellerScopeWithoutPagination() {
        var options = service.saleFilterOptions();
        assertThat((java.util.List<Map<String, Object>>) options.get("customers"))
            .extracting(row -> row.get("id")).containsExactlyInAnyOrder(north, south);
        assertThat((java.util.List<Map<String, Object>>) options.get("sellers"))
            .extracting(row -> row.get("id")).containsExactlyInAnyOrder(ana, lucia);
        CurrentUserAccess access = mock(CurrentUserAccess.class);
        when(access.isAdmin()).thenReturn(false);
        when(access.requireSellerProfile()).thenReturn(ana);
        var scoped = new ReadQueryService(jdbc, access).saleFilterOptions();
        assertThat((java.util.List<Map<String, Object>>) scoped.get("customers"))
            .extracting(row -> row.get("id")).containsExactly(north);
        assertThat((java.util.List<Map<String, Object>>) scoped.get("sellers"))
            .extracting(row -> row.get("id")).containsExactly(ana);
    }

    @Test
    void dateRangeIncludesWholeArgentineDaysAndCombinesWithIdsBalanceAndPagination() {
        boundarySale("BOUND-BEFORE", "2026-09-30T02:59:59.999999Z");
        boundarySale("BOUND-START", "2026-09-30T03:00:00Z");
        boundarySale("BOUND-END", "2026-10-01T02:59:59.999999Z");
        boundarySale("BOUND-AFTER", "2026-10-01T03:00:00Z");
        LocalDate day = LocalDate.of(2026, 9, 30);
        var first = service.sales(0, 1, "BOUND", north, ana, true, day, day);
        var second = service.sales(1, 1, "BOUND", north, ana, true, day, day);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.content()).extracting(row -> row.get("number")).containsExactly("BOUND-END");
        assertThat(second.content()).extracting(row -> row.get("number")).containsExactly("BOUND-START");
        assertThat(service.sales(0, 20, "BOUND", north, ana, true, null, day).totalElements()).isEqualTo(3);
        assertThat(service.sales(0, 20, "BOUND", north, ana, true, day, null).totalElements()).isEqualTo(3);
        assertThat(service.sales(0, 20, "BOUND", south, ana, true, day, day).totalElements()).isZero();
        CurrentUserAccess access = mock(CurrentUserAccess.class);
        when(access.isAdmin()).thenReturn(false);
        when(access.requireSellerProfile()).thenReturn(lucia);
        assertThat(new ReadQueryService(jdbc, access).sales(0, 20, "BOUND", north, ana, true, day, day).totalElements()).isZero();
        assertThatThrownBy(() -> service.sales(0, 20, "", null, null, false, day.plusDays(1), day))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private void boundarySale(String number, String timestamp) {
        sale(number, north, ana, 0, "CONFIRMED");
        jdbc.update("UPDATE sale.sales SET created_at = ? WHERE sale_number = ?", Timestamp.from(Instant.parse(timestamp)), number);
    }

    private void sale(String number, UUID customer, UUID seller, int paid, String status) {
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO orders.orders VALUES (?, ?)", order, seller);
        jdbc.update("INSERT INTO sale.sales VALUES (?, ?, ?, ?, 100, ?, ?, TIMESTAMP '2026-09-30 12:00:00')",
            UUID.randomUUID(), number, order, customer, paid, status);
    }
}
