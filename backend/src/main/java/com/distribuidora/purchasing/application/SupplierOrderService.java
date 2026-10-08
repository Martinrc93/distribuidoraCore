package com.distribuidora.purchasing.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class SupplierOrderService {
    public record LineInput(UUID productId, BigDecimal quantity, BigDecimal unitCost) { }
    public record OrderInput(UUID supplierId, LocalDate orderDate, List<LineInput> lines, String idempotencyKey) { }
    public record OrderResult(UUID id, String number, BigDecimal total) { }
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;

    public SupplierOrderService(JdbcTemplate jdbc, AuditService audit, CurrentUserAccess currentUser) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional
    public OrderResult create(OrderInput input) {
        validate(input);
        String fingerprint = fingerprint(input);
        var existing = existing(input.idempotencyKey());
        if (!existing.isEmpty()) return replay(existing.getFirst(), fingerprint);
        String supplierName = jdbc.queryForObject("select name from supplier.suppliers where id = ? for share",
            String.class, input.supplierId());
        var resolved = new ArrayList<Map<String, Object>>();
        BigDecimal total = BigDecimal.ZERO.setScale(4);
        for (LineInput line : input.lines().stream().sorted(Comparator.comparing(line -> line.productId().toString())).toList()) {
            var product = jdbc.queryForMap("select id, name, status, cost from catalog.products where id = ? for share", line.productId());
            if (!"ACTIVE".equals(product.get("status"))) throw new IllegalArgumentException("El pedido contiene un producto inactivo");
            BigDecimal cost = line.unitCost() == null ? (BigDecimal) product.get("cost") : line.unitCost();
            decimal(cost, false);
            BigDecimal lineTotal = line.quantity().multiply(cost).setScale(4, RoundingMode.HALF_UP);
            decimal(lineTotal, false);
            total = total.add(lineTotal);
            resolved.add(Map.of("productId", line.productId(), "name", product.get("name"),
                "quantity", line.quantity(), "unitCost", cost, "lineTotal", lineTotal));
        }
        decimal(total, false);
        UUID id = UUID.randomUUID();
        Long sequence = jdbc.queryForObject("select nextval('purchasing.supplier_order_number_seq')", Long.class);
        String number = "PRV-%08d".formatted(sequence);
        var inserted = jdbc.queryForList("""
            insert into purchasing.supplier_orders(id, order_number, supplier_id, supplier_name, order_date,
                total, idempotency_key, request_fingerprint, created_by, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (idempotency_key) do nothing returning id
            """, id, number, input.supplierId(), supplierName, input.orderDate(), total,
            input.idempotencyKey(), fingerprint, currentUser.userId(), Timestamp.from(Instant.now()));
        if (inserted.isEmpty()) return replay(existing(input.idempotencyKey()).getFirst(), fingerprint);
        for (var line : resolved) jdbc.update("""
            insert into purchasing.supplier_order_items(id, order_id, product_id, product_name, quantity, unit_cost, line_total)
            values (?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), id, line.get("productId"), line.get("name"), line.get("quantity"), line.get("unitCost"), line.get("lineTotal"));
        audit.record(currentUser.userId(), "SUPPLIER_ORDER_CREATE", "SUPPLIER_ORDER", id.toString(), "SUCCESS",
            Map.of("supplierId", input.supplierId().toString(), "number", number, "total", total));
        return new OrderResult(id, number, total);
    }

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> list(int page, int size, UUID supplierId, LocalDate dateMin, LocalDate dateMax) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Paginación inválida");
        if (dateMin != null && dateMax != null && dateMin.isAfter(dateMax)) throw new IllegalArgumentException("La fecha inicial no puede ser posterior a la final");
        var parameters = new ArrayList<Object>();
        String where = " where 1 = 1";
        if (supplierId != null) { where += " and supplier_id = ?"; parameters.add(supplierId); }
        if (dateMin != null) { where += " and order_date >= ?"; parameters.add(dateMin); }
        if (dateMax != null) { where += " and order_date <= ?"; parameters.add(dateMax); }
        Long total = jdbc.queryForObject("select count(*) from purchasing.supplier_orders" + where, Long.class, parameters.toArray());
        parameters.add(size);
        parameters.add((long) page * size);
        var rows = jdbc.queryForList("select id, order_number as number, supplier_id as \"supplierId\", supplier_name as supplier, "
            + "order_date as date, total from purchasing.supplier_orders" + where + " order by order_date desc, created_at desc, id desc limit ? offset ?",
            parameters.toArray());
        rows.forEach(row -> row.put("date", row.get("date").toString()));
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(UUID id) {
        var order = jdbc.queryForMap("select id, order_number as number, supplier_id as \"supplierId\", supplier_name as supplier, "
            + "order_date as date, total from purchasing.supplier_orders where id = ?", id);
        order.put("date", order.get("date").toString());
        var items = jdbc.queryForList("""
            select i.product_id as "productId", i.product_name as "productName", i.quantity,
                   i.unit_cost as "unitCost", i.line_total as "lineTotal", p.status, p.cost as "currentCost"
            from purchasing.supplier_order_items i join catalog.products p on p.id = i.product_id
            where i.order_id = ? order by i.product_name, i.product_id
            """, id);
        return Map.of("order", order, "items", items);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> lastOrder(UUID supplierId) {
        jdbc.queryForObject("select id from supplier.suppliers where id = ?", UUID.class, supplierId);
        var ids = jdbc.queryForList("select id from purchasing.supplier_orders where supplier_id = ? "
            + "order by order_date desc, created_at desc, id desc limit 1", UUID.class, supplierId);
        if (ids.isEmpty()) return Map.of("available", false);
        var result = new HashMap<>(detail(ids.getFirst()));
        result.put("available", true);
        return result;
    }

    private List<Map<String, Object>> existing(String key) {
        return jdbc.queryForList("select id, order_number as number, total, request_fingerprint from purchasing.supplier_orders where idempotency_key = ?", key);
    }

    private OrderResult replay(Map<String, Object> row, String fingerprint) {
        if (!fingerprint.equals(row.get("request_fingerprint"))) throw new IllegalStateException("La clave del pedido ya se utilizó con otros datos");
        return new OrderResult((UUID) row.get("id"), (String) row.get("number"), (BigDecimal) row.get("total"));
    }

    public static void validate(OrderInput input) {
        if (input == null || input.supplierId() == null || input.orderDate() == null || input.lines() == null
            || input.lines().isEmpty() || input.lines().size() > 1000 || input.idempotencyKey() == null
            || input.idempotencyKey().isBlank() || input.idempotencyKey().length() > 100) throw new IllegalArgumentException("Los datos del pedido son inválidos");
        var seen = new HashSet<UUID>();
        for (LineInput line : input.lines()) {
            if (line == null || line.productId() == null || !seen.add(line.productId())) throw new IllegalArgumentException("Los productos son inválidos o están repetidos");
            decimal(line.quantity(), true);
            if (line.unitCost() != null) decimal(line.unitCost(), false);
        }
    }

    private static void decimal(BigDecimal value, boolean positive) {
        if (value == null || value.signum() < 0 || (positive && value.signum() == 0) || value.scale() > 4
            || value.abs().compareTo(new BigDecimal("1000000000000000")) >= 0) throw new IllegalArgumentException("Las cantidades e importes deben ser válidos, con hasta cuatro decimales");
    }

    private String fingerprint(OrderInput input) {
        String lines = input.lines().stream().sorted(Comparator.comparing(line -> line.productId().toString()))
            .map(line -> line.productId() + ":" + line.quantity().stripTrailingZeros().toPlainString() + ":"
                + (line.unitCost() == null ? "AUTO" : line.unitCost().stripTrailingZeros().toPlainString())).reduce("", (a, b) -> a + "|" + b);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (input.supplierId() + "|" + input.orderDate() + lines).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
