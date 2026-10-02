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
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardSummaryTest {
    private JdbcTemplate jdbc;
    private ReadQueryService service;
    private final LocalDate day = LocalDate.of(2026, 9, 30);
    private final UUID seller = UUID.randomUUID();
    private final UUID otherSeller = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create schema orders");
        jdbc.execute("create schema sale");
        jdbc.execute("create schema seller");
        jdbc.execute("create schema customer");
        jdbc.execute("create schema payment");
        jdbc.execute("create table seller.seller_profiles(id uuid primary key, display_name varchar, status varchar)");
        jdbc.execute("create table orders.orders(id uuid primary key, seller_id uuid, status varchar, created_at timestamp with time zone)");
        jdbc.execute("create table sale.sales(id uuid primary key, order_id uuid unique, status varchar, total decimal(19,4), paid decimal(19,4))");
        jdbc.execute("create table customer.account_ledger(sale_id uuid, entry_type varchar, amount decimal(19,4))");
        jdbc.execute("create table payment.payments(sale_id uuid, method varchar, amount decimal(19,4))");
        jdbc.update("insert into seller.seller_profiles values (?, 'Lucía', 'ACTIVE'), (?, 'Lucía', 'INACTIVE')", seller, otherSeller);
        service = new ReadQueryService(jdbc);
    }

    @Test
    void aggregatesOrdersAndTheirCurrentPaymentsAndLedgerBySellerWithoutMultiplyingRows() {
        UUID delivered = insert(seller, "DELIVERED", "100.1250", "40.0250", "2026-09-30T03:00:00Z");
        jdbc.update("update payment.payments set amount = 20.0250 where sale_id = ?", delivered);
        jdbc.update("insert into payment.payments values (?, 'BANK_TRANSFER', 20)", delivered);
        ledger(delivered, "DEBIT", "80.1000");
        ledger(delivered, "CREDIT", "20.0000");
        UUID confirmed = insert(seller, "CONFIRMED", "200", "0", "2026-10-01T02:59:59.999999Z");
        ledger(confirmed, "DEBIT", "200");
        UUID cancelled = insert(seller, "CANCELLED", "900", "0", "2026-09-30T12:00:00Z");
        ledger(cancelled, "DEBIT", "900");
        ledger(cancelled, "CREDIT", "900");
        insert(otherSeller, "DELIVERED", "50", "50", "2026-09-30T10:00:00Z");
        UUID unassigned = insert(null, "CONFIRMED", "30", "10", "2026-09-30T11:00:00Z");
        ledger(unassigned, "DEBIT", "20");
        UUID before = insert(seller, "DELIVERED", "7000", "0", "2026-09-30T02:59:59.999999Z");
        ledger(before, "DEBIT", "7000");
        insert(seller, "CONFIRMED", "8000", "0", "2026-10-01T03:00:00Z");

        Map<String, Object> result = service.dashboard(day, day);
        Map<String, Object> totals = totals(result);
        assertAmount(totals, "performedOrders", "5");
        assertAmount(totals, "deliveredOrders", "2");
        assertAmount(totals, "totalBilled", "380.1250");
        assertAmount(totals, "totalPaid", "100.0250");
        assertAmount(totals, "cashPaid", "80.0250");
        assertAmount(totals, "transferPaid", "20");
        assertAmount(totals, "accountBalance", "280.1000");
        List<Map<String, Object>> rows = rows(result);
        assertThat(rows).hasSize(3);
        Map<String, Object> own = rows.stream().filter(row -> seller.equals(row.get("sellerId"))).findFirst().orElseThrow();
        assertAmount(own, "performedOrders", "3");
        assertAmount(own, "totalBilled", "300.1250");
        assertAmount(own, "accountBalance", "260.1000");
        assertAmount(own, "cashPaid", "20.0250");
        assertAmount(own, "transferPaid", "20");
        assertThat(rows).anySatisfy(row -> assertThat(row.get("sellerId")).isEqualTo(otherSeller))
            .anySatisfy(row -> assertThat(row.get("sellerId")).isNull());
    }

    @Test
    void usesLedgerDebtAndCapsItAtTheUnpaidSaleAmount() {
        UUID lowerDebt = insert(seller, "DELIVERED", "100", "20", "2026-09-30T12:00:00Z");
        ledger(lowerDebt, "DEBIT", "50");
        ledger(lowerDebt, "CREDIT", "20");
        UUID excessiveDebt = insert(seller, "CONFIRMED", "100", "80", "2026-09-30T12:00:00Z");
        ledger(excessiveDebt, "DEBIT", "100");
        UUID credit = insert(seller, "DELIVERED", "100", "100", "2026-09-30T12:00:00Z");
        ledger(credit, "CREDIT", "20");
        assertAmount(totals(service.dashboard(day, day)), "accountBalance", "50");
    }

    @Test
    void supportsMultipleDaysAndReturnsZeroMetricsForAnEmptyPeriod() {
        insert(seller, "CONFIRMED", "10", "10", "2026-09-30T12:00:00Z");
        insert(seller, "DELIVERED", "20", "20", "2026-10-01T12:00:00Z");
        assertAmount(totals(service.dashboard(day, day.plusDays(1))), "totalBilled", "30");
        var empty = service.dashboard(day.minusDays(1), day.minusDays(1));
        assertThat(rows(empty)).isEmpty();
        assertThat(totals(empty).values()).allMatch(value -> ((BigDecimal) value).signum() == 0);
    }

    @Test
    void defaultsToTheArgentineDayAndRejectsReversedDates() {
        LocalDate today = LocalDate.now(ZoneId.of("America/Argentina/Buenos_Aires"));
        var result = service.dashboard(null, null);
        assertThat(result).containsEntry("dateMin", today).containsEntry("dateMax", today);
        assertThatThrownBy(() -> service.dashboard(day.plusDays(1), day)).isInstanceOf(IllegalArgumentException.class);
    }

    private UUID insert(UUID sellerId, String status, String total, String paid, String timestamp) {
        UUID orderId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        jdbc.update("insert into orders.orders values (?, ?, ?, ?)", orderId, sellerId, status, Timestamp.from(Instant.parse(timestamp)));
        jdbc.update("insert into sale.sales values (?, ?, ?, ?, ?)", saleId, orderId, status, new BigDecimal(total), new BigDecimal(paid));
        if (new BigDecimal(paid).signum() > 0) jdbc.update("insert into payment.payments values (?, 'CASH', ?)", saleId, new BigDecimal(paid));
        return saleId;
    }

    private void ledger(UUID saleId, String type, String amount) {
        jdbc.update("insert into customer.account_ledger values (?, ?, ?)", saleId, type, new BigDecimal(amount));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> totals(Map<String, Object> result) { return (Map<String, Object>) result.get("totals"); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> result) { return (List<Map<String, Object>>) result.get("bySeller"); }

    private void assertAmount(Map<String, Object> row, String key, String value) {
        assertThat(new BigDecimal(row.get(key).toString())).isEqualByComparingTo(value);
    }
}
