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
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ExpenseService {
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
    public PageResponse<Entry> list(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        long total = jdbc.queryForObject("select count(*) from expense.expenses", Long.class);
        var entries = jdbc.query("""
            select id, amount, type, observation, created_at from expense.expenses
            order by created_at desc, id desc limit ? offset ?
            """, ENTRY, safeSize, (long) safePage * safeSize);
        return PageResponse.of(entries, safePage, safeSize, total);
    }

    @Transactional
    public Entry create(UUID id, BigDecimal amount, String type, String observation) {
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
