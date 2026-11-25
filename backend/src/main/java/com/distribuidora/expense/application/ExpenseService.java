package com.distribuidora.expense.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.distribuidora.shared.time.BusinessTime;

@Service
public class ExpenseService {
    private static final ZoneId BUSINESS_ZONE = BusinessTime.ZONE;
    private static final RowMapper<Entry> ENTRY = (row, index) -> new Entry(row.getObject("id", UUID.class),
        row.getBigDecimal("amount"), row.getString("type"), row.getString("observation"),
        row.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;
    private final CurrentUserAccess currentUser;
    private final AuditService audit;

    public ExpenseService(JdbcTemplate jdbc, CurrentUserAccess currentUser, AuditService audit) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PageResponse<Entry> list(int page, int size, String type, LocalDate dateMin, LocalDate dateMax) {
        validateYear(dateMin);
        validateYear(dateMax);
        if (dateMin != null && dateMax != null && dateMin.isAfter(dateMax)) {
            throw new IllegalArgumentException("La fecha inicial no puede ser posterior a la final");
        }

        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        var filters = new StringBuilder(" where 1 = 1");
        var parameters = new ArrayList<>();
        if (type != null && !type.isEmpty()) {
            filters.append(" and type = ?");
            parameters.add(type);
        }
        if (dateMin != null) {
            filters.append(" and created_at >= ?");
            parameters.add(Timestamp.from(dateMin.atStartOfDay(BUSINESS_ZONE).toInstant()));
        }
        if (dateMax != null) {
            filters.append(" and created_at < ?");
            parameters.add(Timestamp.from(dateMax.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant()));
        }
        long total = Objects.requireNonNullElse(jdbc.queryForObject("select count(*) from expense.expenses" + filters, Long.class, parameters.toArray()), 0L);
        parameters.add(safeSize);
        parameters.add((long) safePage * safeSize);
        var entries = jdbc.query("""
            select id, amount, type, observation, created_at from expense.expenses
            """ + filters + " order by created_at desc, id desc limit ? offset ?", ENTRY, parameters.toArray());
        return PageResponse.of(entries, safePage, safeSize, total);
    }

    private static void validateYear(LocalDate date) {
        if (date != null && (date.getYear() < 1900 || date.getYear() > 9999)) {
            throw new IllegalArgumentException("El año debe estar entre 1900 y 9999");
        }
    }

    @Transactional
    public Entry create(UUID id, BigDecimal amount, String type, String observation) {
        if (id == null) {
            throw new IllegalArgumentException("El identificador de idempotencia es obligatorio");
        }
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2
            || amount.compareTo(new BigDecimal("999999999999.99")) > 0) {
            throw new IllegalArgumentException("El monto debe ser positivo, con hasta 2 decimales y dentro del límite permitido");
        }
        if (type == null || !java.util.Set.of("PETTY_CASH", "OTHER", "SERVICES", "FUEL").contains(type)) {
            throw new IllegalArgumentException("El tipo de gasto no es válido");
        }
        if (observation != null && observation.length() > 500) {
            throw new IllegalArgumentException("La observación no puede superar los 500 caracteres");
        }
        UUID actor = currentUser.userId();
        String note = observation == null || observation.isBlank() ? null : observation.strip();
        var inserted = jdbc.query("""
            insert into expense.expenses (id, amount, type, observation, created_by) values (?, ?, ?, ?, ?)
            on conflict (id) do nothing returning id, amount, type, observation, created_at
            """, ENTRY, id, amount, type, note, actor);
        if (!inserted.isEmpty()) {
            audit.recordWithinTransaction(actor, "EXPENSE_CREATE", "EXPENSE", id.toString(), "SUCCESS",
                Map.of("amount", amount, "type", type));
            return inserted.getFirst();
        }

        // A repeated submission returns the original entry after the winning transaction commits.
        var saved = jdbc.query("""
            select id, amount, type, observation, created_at from expense.expenses where id = ? and created_by = ?
            """, ENTRY, id, actor);
        if (!saved.isEmpty()) {
            Entry existing = saved.getFirst();
            if (amount.compareTo(existing.amount()) == 0 && type.equals(existing.type())
                && Objects.equals(note, existing.observation())) return existing;
        }
        throw new IllegalStateException("La clave de carga ya se usó para otro gasto");
    }

    public record Entry(UUID id, BigDecimal amount, String type, String observation, Instant createdAt) { }
}
