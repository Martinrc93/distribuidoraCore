package com.distribuidora.sale.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CommissionSettlementService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    public record Pending(String token, long entryCount, String amount) { }
    public record Movement(UUID id, UUID orderId, String orderNumber, String product, boolean returned,
                           String amount, Instant occurredAt, Instant paidAt) { }
    public record Statement(String seller, Pending pending, String periodAmount, PageResponse<Movement> movements) { }
    public record Settlement(UUID id, String amount, long entryCount, Instant paidAt) { }
    private record Totals(long count, BigDecimal amount) { }

    private final JdbcTemplate jdbc;
    private final CurrentUserAccess user;
    private final AuditService audit;

    public CommissionSettlementService(JdbcTemplate jdbc, CurrentUserAccess user, AuditService audit) {
        this.jdbc = jdbc;
        this.user = user;
        this.audit = audit;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Statement statement(UUID sellerId, LocalDate dateMin, LocalDate dateMax, int page, int size) {
        requireAdmin();
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        LocalDate from = dateMin == null ? today.withDayOfMonth(1) : dateMin;
        LocalDate to = dateMax == null ? today : dateMax;
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (from.getYear() < 1900 || to.isAfter(today) || days < 1 || days > 366) {
            throw new IllegalArgumentException("Elegí un período válido de hasta 366 días, sin fechas futuras");
        }
        String seller = jdbc.queryForObject("select display_name from seller.seller_profiles where id = ?", String.class, sellerId);
        Timestamp start = Timestamp.from(from.atStartOfDay(BUSINESS_ZONE).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant());
        int number = Math.max(0, page);
        int limit = Math.max(1, Math.min(100, size));
        String filter = " where e.seller_id = ? and e.occurred_at >= ? and e.occurred_at < ? ";
        Totals totals = jdbc.queryForObject("select count(*), coalesce(sum(e.amount), 0) from sale.commission_entries e" + filter,
            (row, index) -> new Totals(row.getLong(1), row.getBigDecimal(2)), sellerId, start, end);
        List<Movement> movements = jdbc.query("""
            select e.id, o.id as order_id, o.order_number, i.product_name, e.return_id is not null as returned,
                   e.amount, e.occurred_at, p.paid_at
            from sale.commission_entries e
            join sale.sale_items i on i.id = e.sale_item_id
            join sale.sales s on s.id = i.sale_id
            join orders.orders o on o.id = s.order_id
            left join sale.commission_settlements p on p.id = e.settlement_id
            """ + filter + " order by e.occurred_at desc, e.id desc limit ? offset ?", (row, index) -> {
                var paidAt = row.getTimestamp("paid_at");
                return new Movement(row.getObject("id", UUID.class), row.getObject("order_id", UUID.class),
                    row.getString("order_number"), row.getString("product_name"), row.getBoolean("returned"),
                    row.getBigDecimal("amount").toPlainString(), row.getTimestamp("occurred_at").toInstant(),
                    paidAt == null ? null : paidAt.toInstant());
            }, sellerId, start, end, limit, (long) limit * number);
        return new Statement(seller, pending(sellerId), totals.amount().toPlainString(),
            PageResponse.of(movements, number, limit, totals.count()));
    }

    @Transactional
    public Settlement settle(UUID sellerId, String token) {
        requireAdmin();
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Confirmá el saldo pendiente");
        // Serialize payouts for one seller. A key-share lock from an accrual can still proceed.
        jdbc.queryForObject("select id from seller.seller_profiles where id = ? for no key update", UUID.class, sellerId);
        Pending pending = pending(sellerId);
        BigDecimal amount = new BigDecimal(pending.amount());
        if (!pending.token().equals(token) || amount.signum() <= 0) {
            throw new IllegalStateException("El saldo pendiente cambió o no es positivo");
        }
        UUID id = UUID.randomUUID();
        UUID actor = user.userId();
        Instant now = Instant.now();
        jdbc.update("insert into sale.commission_settlements(id, seller_id, amount, paid_at, paid_by) values (?, ?, ?, ?, ?)",
            id, sellerId, amount, Timestamp.from(now), actor);
        int updated = jdbc.update("update sale.commission_entries set settlement_id = ? where seller_id = ? and settlement_id is null",
            id, sellerId);
        if (updated != pending.entryCount()) throw new IllegalStateException("El saldo pendiente cambió");
        audit.recordWithinTransaction(actor, "COMMISSION_SETTLEMENT", "SELLER", sellerId.toString(), "SUCCESS",
            Map.of("settlementId", id.toString(), "amount", amount, "entryCount", updated));
        return new Settlement(id, amount.toPlainString(), updated, now);
    }

    private Pending pending(UUID sellerId) {
        return jdbc.queryForObject("""
            select coalesce(md5(string_agg(id::text, ',' order by id)), '') as token,
                   count(*) as items, coalesce(sum(amount), 0) as amount
            from sale.commission_entries
            where seller_id = ? and settlement_id is null
            """, (row, index) -> new Pending(row.getString("token"), row.getLong("items"),
                row.getBigDecimal("amount").toPlainString()), sellerId);
    }

    private void requireAdmin() {
        if (!user.isAdmin()) throw new AccessDeniedException("ADMIN_ALL es requerido para consultar y liquidar comisiones");
    }
}
