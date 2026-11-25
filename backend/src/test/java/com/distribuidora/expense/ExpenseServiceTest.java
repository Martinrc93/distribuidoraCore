package com.distribuidora.expense;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.expense.application.ExpenseService;
import com.distribuidora.expense.application.ExpenseService.Entry;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExpenseServiceTest {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");

    private JdbcTemplate jdbc;
    private CurrentUserAccess currentUser;
    private AuditService audit;
    private ExpenseService service;
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        currentUser = mock(CurrentUserAccess.class);
        audit = mock(AuditService.class);
        when(currentUser.userId()).thenReturn(actorId);
        service = new ExpenseService(jdbc, currentUser, audit);
    }

    @Test
    void listsWithTypeAndBusinessDayBoundaries() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 2);
        var entry = new Entry(UUID.randomUUID(), new BigDecimal("500.00"), "PETTY_CASH", "Cafetería", Instant.parse("2026-10-01T14:00:00Z"));
        when(jdbc.queryForObject(contains("select count(*) from expense.expenses"), eq(Long.class),
            eq("PETTY_CASH"),
            eq(Timestamp.from(from.atStartOfDay(BUSINESS_ZONE).toInstant())),
            eq(Timestamp.from(to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant()))))
            .thenReturn(1L);
        when(jdbc.query(contains("from expense.expenses"), ArgumentMatchers.<RowMapper<Entry>>any(),
            eq("PETTY_CASH"),
            eq(Timestamp.from(from.atStartOfDay(BUSINESS_ZONE).toInstant())),
            eq(Timestamp.from(to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant())),
            eq(25),
            eq(25L)))
            .thenReturn(List.of(entry));

        var page = service.list(1, 25, "PETTY_CASH", from, to);

        assertThat(page.content()).containsExactly(entry);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(25);
        assertThat(page.totalElements()).isEqualTo(1L);
    }

    @Test
    void rejectsInvertedDateRangesAndOutOfBoundsYears() {
        assertThatThrownBy(() -> service.list(0, 20, "", LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 1)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(0, 20, "", LocalDate.of(1899, 12, 31), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(0, 20, "", null, LocalDate.of(10000, 1, 1)))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void createsExpenseNormalizesObservationAndRecordsAudit() {
        UUID key = UUID.randomUUID();
        var created = new Entry(key, new BigDecimal("120.50"), "FUEL", "Flete", Instant.parse("2026-10-03T12:00:00Z"));
        when(jdbc.query(contains("on conflict (id) do nothing returning"), ArgumentMatchers.<RowMapper<Entry>>any(),
            eq(key), eq(new BigDecimal("120.50")), eq("FUEL"), eq("Flete"), eq(actorId)))
            .thenReturn(List.of(created));

        var result = service.create(key, new BigDecimal("120.50"), "FUEL", "  Flete  ");

        assertThat(result).isEqualTo(created);
        verify(audit).recordWithinTransaction(actorId, "EXPENSE_CREATE", "EXPENSE", key.toString(), "SUCCESS",
            Map.of("amount", new BigDecimal("120.50"), "type", "FUEL"));
    }

    @Test
    void replaysIdempotentCreateWhenPayloadMatchesAndRejectsMismatch() {
        UUID key = UUID.randomUUID();
        var existing = new Entry(key, new BigDecimal("99.00"), "OTHER", null, Instant.parse("2026-10-03T12:00:00Z"));
        when(jdbc.query(contains("on conflict (id) do nothing returning"), ArgumentMatchers.<RowMapper<Entry>>any(),
            any(), any(), any(), any(), any()))
            .thenReturn(List.of());
        when(jdbc.query(contains("where id = ? and created_by = ?"), ArgumentMatchers.<RowMapper<Entry>>any(),
            eq(key), eq(actorId)))
            .thenReturn(List.of(existing));

        assertThat(service.create(key, new BigDecimal("99.00"), "OTHER", "   ")).isEqualTo(existing);
        verifyNoInteractions(audit);

        assertThatThrownBy(() -> service.create(key, new BigDecimal("100.00"), "OTHER", null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidAmountsTypesAndLongObservations() {
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(null, new BigDecimal("10"), "FUEL", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(key, BigDecimal.ZERO, "FUEL", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(key, new BigDecimal("10.123"), "FUEL", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(key, new BigDecimal("1000000000000.00"), "FUEL", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(key, new BigDecimal("10"), "INVALID", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(key, new BigDecimal("10"), "FUEL", "x".repeat(501))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc, audit);
    }
}
