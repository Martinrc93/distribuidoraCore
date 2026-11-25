package com.distribuidora.inventory.application;

import com.distribuidora.shared.web.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import com.distribuidora.shared.time.BusinessTime;

@Service
public class StockShrinkageQueryService {
    private static final ZoneId BUSINESS_ZONE = BusinessTime.ZONE;
    private final JdbcTemplate jdbc;

    public StockShrinkageQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(UUID id, UUID productId, String product, BigDecimal quantity, String reason, Instant date) { }
    public record Report(BigDecimal totalQuantity, PageResponse<Entry> movements) { }
    private record Totals(long count, BigDecimal quantity) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report report(int page, int size, LocalDate dateMin, LocalDate dateMax) {
        validateYear(dateMin);
        validateYear(dateMax);
        if (dateMin != null && dateMax != null && dateMin.isAfter(dateMax)) {
            throw new IllegalArgumentException("La fecha inicial no puede ser posterior a la final");
        }
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        StringBuilder filters = new StringBuilder(" where m.movement_type = 'SHRINKAGE'");
        var parameters = new ArrayList<>();
        if (dateMin != null) {
            filters.append(" and m.created_at >= ?");
            parameters.add(Timestamp.from(dateMin.atStartOfDay(BUSINESS_ZONE).toInstant()));
        }
        if (dateMax != null) {
            filters.append(" and m.created_at < ?");
            parameters.add(Timestamp.from(dateMax.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant()));
        }
        Totals totals = Objects.requireNonNullElse(jdbc.queryForObject("""
            select count(*), coalesce(sum(-m.quantity), 0) from inventory.stock_movements m
            """ + filters, (row, i) -> new Totals(row.getLong(1), row.getBigDecimal(2)), parameters.toArray()),
            new Totals(0L, BigDecimal.ZERO));
        parameters.add(safeSize);
        parameters.add((long) safePage * safeSize);
        var rows = jdbc.query("""
            select m.id, m.product_id, p.name, -m.quantity as quantity, m.reason, m.created_at
            from inventory.stock_movements m join catalog.products p on p.id = m.product_id
            """ + filters + " order by m.created_at desc, m.id desc limit ? offset ?",
            (row, i) -> new Entry(row.getObject("id", UUID.class), row.getObject("product_id", UUID.class),
                row.getString("name"), row.getBigDecimal("quantity"), row.getString("reason"),
                row.getTimestamp("created_at").toInstant()),
            parameters.toArray());
        return new Report(totals.quantity(), PageResponse.of(rows, safePage, safeSize, totals.count()));
    }

    private void validateYear(LocalDate date) {
        if (date != null && (date.getYear() < 1900 || date.getYear() > 9999)) {
            throw new IllegalArgumentException("El año debe estar entre 1900 y 9999");
        }
    }
}
