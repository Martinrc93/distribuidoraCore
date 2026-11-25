package com.distribuidora.purchasing.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.shared.security.CurrentUserAccess;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class SupplierOrderService {
    public record LineInput(
        @jakarta.validation.constraints.NotNull UUID productId,
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin(value = "0.0000", inclusive = false)
        @jakarta.validation.constraints.Digits(integer = 15, fraction = 4) BigDecimal quantity,
        @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.Digits(integer = 15, fraction = 4) BigDecimal unitCost
    ) { }

    /** The legacy confirm flag is retained for replaying requests accepted before V56. */
    public record OrderInput(
        UUID supplierId,
        LocalDate orderDate,
        @jakarta.validation.constraints.Size(max = 1000) List<@jakarta.validation.constraints.NotNull @jakarta.validation.Valid LineInput> lines,
        @jakarta.validation.constraints.Size(max = 100) String idempotencyKey,
        Boolean confirm
    ) {
        public OrderInput(UUID supplierId, LocalDate orderDate, List<LineInput> lines, String idempotencyKey) {
            this(supplierId, orderDate, lines, idempotencyKey, null);
        }

        public boolean confirms() { return Boolean.TRUE.equals(confirm); }
    }

    public record OrderResult(UUID id, String number, BigDecimal total, String status) {
        public OrderResult(UUID id, String number, BigDecimal total) { this(id, number, total, null); }
    }

    private static final BigDecimal HALF = new BigDecimal("0.5");
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;
    private final InventoryMovementService movements;
    private final SupplierOrderQueryService queries;

    public SupplierOrderService(JdbcTemplate jdbc, AuditService audit, CurrentUserAccess currentUser,
            InventoryMovementService movements, SupplierOrderQueryService queries) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.currentUser = currentUser;
        this.movements = movements;
        this.queries = queries;
    }

    @Transactional
    public OrderResult create(OrderInput input) {
        validate(input);
        String fingerprint = fingerprint(input);
        jdbc.query("select pg_advisory_xact_lock(hashtext(?))", rs -> null, "supplier-order:" + input.idempotencyKey());
        var existing = existing(input.idempotencyKey());
        if (!existing.isEmpty()) return replay(existing.getFirst(), fingerprint);
        if (input.confirms()) throw new IllegalArgumentException("Guardá la compra como pendiente antes de confirmarla");
        String supplierName = supplierName(input.supplierId());
        var resolved = resolveLines(input.lines(), Map.of());
        BigDecimal total = total(resolved);
        UUID id = UUID.randomUUID();
        Long sequence = jdbc.queryForObject("select nextval('purchasing.supplier_order_number_seq')", Long.class);
        String number = "PRV-%08d".formatted(sequence);
        Timestamp now = Timestamp.from(Instant.now());
        var inserted = jdbc.queryForList("""
            insert into purchasing.supplier_orders(id, order_number, supplier_id, supplier_name, order_date,
                total, idempotency_key, request_fingerprint, created_by, created_at, status, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
            on conflict (idempotency_key) do nothing returning id
            """, id, number, input.supplierId(), supplierName, input.orderDate(), total,
            input.idempotencyKey(), fingerprint, currentUser.userId(), now, now);
        if (inserted.isEmpty()) return replay(existing(input.idempotencyKey()).getFirst(), fingerprint);
        insertItems(id, resolved);
        audit.recordWithinTransaction(currentUser.userId(), "SUPPLIER_ORDER_CREATE", "SUPPLIER_ORDER", id.toString(), "SUCCESS",
            Map.of("supplierId", input.supplierId().toString(), "number", number, "total", total));
        return new OrderResult(id, number, total, "PENDING");
    }

    /** Confirms a pending purchase and adds each line to stock. */
    @Transactional
    public Map<String, Object> confirm(UUID id) {
        var order = lockOrder(id);
        if (!"PENDING".equals(order.get("status"))) throw new IllegalStateException("Solo se pueden confirmar compras pendientes");
        var lines = jdbc.queryForList("""
            select product_id as "productId", quantity from purchasing.supplier_order_items
            where order_id = ? order by product_id
            """, id);
        applyConfirmation(id, lines, Timestamp.from(Instant.now()));
        return queries.loadDetail(id);
    }

    /**
     * Replaces supplier, date and lines. On a CONFIRMED purchase the stock receives the difference per product
     * (PURCHASE_ADJUSTMENT); pending and legacy purchases do not touch stock. Cancelled purchases cannot change.
     */
    @Transactional
    public Map<String, Object> update(UUID id, OrderInput input) {
        validateLines(input == null ? null : input.supplierId(), input == null ? null : input.orderDate(), input == null ? null : input.lines());
        var order = lockOrder(id);
        String status = (String) order.get("status");
        if ("CANCELLED".equals(status)) throw new IllegalStateException("Una compra anulada no puede modificarse");
        if (!"LEGACY".equals(status)) requireHalfUnits(input.lines());
        String supplierName = supplierName(input.supplierId());
        Map<UUID, BigDecimal> before = quantities(jdbc.queryForList(
            "select product_id as \"productId\", quantity from purchasing.supplier_order_items where order_id = ?", id));
        var resolved = resolveLines(input.lines(), before);
        BigDecimal total = total(resolved);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("delete from purchasing.supplier_order_items where order_id = ?", id);
        insertItems(id, resolved);
        jdbc.update("""
            update purchasing.supplier_orders set supplier_id = ?, supplier_name = ?, order_date = ?, total = ?, updated_at = ?
            where id = ?
            """, input.supplierId(), supplierName, input.orderDate(), total, now, id);
        List<Map<String, Object>> deltas = new ArrayList<>();
        if ("CONFIRMED".equals(status)) {
            Map<UUID, BigDecimal> after = quantities(resolved);
            var products = new TreeSet<>(Comparator.comparing(UUID::toString));
            products.addAll(before.keySet());
            products.addAll(after.keySet());
            for (UUID productId : products) {
                BigDecimal delta = after.getOrDefault(productId, BigDecimal.ZERO).subtract(before.getOrDefault(productId, BigDecimal.ZERO));
                if (delta.signum() == 0) continue;
                movements.apply(productId, delta, "PURCHASE_ADJUSTMENT", id, "Edición de compra " + order.get("number"));
                deltas.add(Map.of("productId", productId.toString(), "quantity", delta));
            }
        }
        audit.recordWithinTransaction(currentUser.userId(), "SUPPLIER_ORDER_UPDATE", "SUPPLIER_ORDER", id.toString(), "SUCCESS",
            Map.of("status", status, "total", total, "stockChanges", deltas));
        return queries.loadDetail(id);
    }

    /** Cancels a purchase. A confirmed one gives its quantities back (PURCHASE_CANCELLATION). */
    @Transactional
    public Map<String, Object> cancel(UUID id) {
        var order = lockOrder(id);
        String status = (String) order.get("status");
        if ("CANCELLED".equals(status)) throw new IllegalStateException("La compra ya está anulada");
        Timestamp now = Timestamp.from(Instant.now());
        if ("CONFIRMED".equals(status)) {
            var lines = jdbc.queryForList("""
                select product_id as "productId", quantity from purchasing.supplier_order_items
                where order_id = ? order by product_id
                """, id);
            for (var line : lines) {
                movements.apply((UUID) line.get("productId"), ((BigDecimal) line.get("quantity")).negate(),
                    "PURCHASE_CANCELLATION", id, "Anulación de compra " + order.get("number"));
            }
        }
        jdbc.update("""
            update purchasing.supplier_orders set status = 'CANCELLED', cancelled_at = ?, cancelled_by = ?, updated_at = ?
            where id = ?
            """, now, currentUser.userId(), now, id);
        audit.recordWithinTransaction(currentUser.userId(), "SUPPLIER_ORDER_CANCEL", "SUPPLIER_ORDER", id.toString(), "SUCCESS",
            Map.of("previousStatus", status, "stockReverted", "CONFIRMED".equals(status)));
        return queries.loadDetail(id);
    }

    private void applyConfirmation(UUID id, List<Map<String, Object>> lines, Timestamp now) {
        String number = jdbc.queryForObject("select order_number from purchasing.supplier_orders where id = ?", String.class, id);
        var sorted = lines.stream().sorted(Comparator.comparing(line -> line.get("productId").toString())).toList();
        for (var line : sorted) {
            BigDecimal quantity = (BigDecimal) line.get("quantity");
            if (quantity.remainder(HALF).signum() != 0) {
                throw new IllegalArgumentException("Las cantidades de una compra confirmada deben ser múltiplos de 0,5");
            }
            movements.apply((UUID) line.get("productId"), quantity, "PURCHASE", id, "Compra " + number);
        }
        jdbc.update("""
            update purchasing.supplier_orders set status = 'CONFIRMED', confirmed_at = ?, confirmed_by = ?, updated_at = ?
            where id = ?
            """, now, currentUser.userId(), now, id);
        audit.recordWithinTransaction(currentUser.userId(), "SUPPLIER_ORDER_CONFIRM", "SUPPLIER_ORDER", id.toString(), "SUCCESS",
            Map.of("number", number, "lines", sorted.size()));
    }

    private Map<String, Object> lockOrder(UUID id) {
        if (id == null) throw new IllegalArgumentException("El identificador de la compra es obligatorio");
        return jdbc.queryForMap("select id, order_number as number, status from purchasing.supplier_orders where id = ? for update", id);
    }

    private String supplierName(UUID supplierId) {
        return jdbc.queryForObject("select name from supplier.suppliers where id = ? for share", String.class, supplierId);
    }

    private List<Map<String, Object>> resolveLines(List<LineInput> lines, Map<UUID, BigDecimal> existingQuantities) {
        var resolved = new ArrayList<Map<String, Object>>();
        for (LineInput line : lines.stream().sorted(Comparator.comparing(line -> line.productId().toString())).toList()) {
            var product = jdbc.queryForMap("select id, name, status, cost from catalog.products where id = ? for share", line.productId());
            BigDecimal previousQuantity = existingQuantities.getOrDefault(line.productId(), BigDecimal.ZERO);
            if (line.quantity().compareTo(previousQuantity) > 0 && !"ACTIVE".equals(product.get("status"))) {
                throw new IllegalArgumentException("El pedido contiene un producto inactivo");
            }
            BigDecimal cost = line.unitCost() == null ? (BigDecimal) product.get("cost") : line.unitCost();
            decimal(cost, false);
            BigDecimal lineTotal = line.quantity().multiply(cost).setScale(4, RoundingMode.HALF_UP);
            decimal(lineTotal, false);
            resolved.add(Map.of("productId", line.productId(), "name", product.get("name"),
                "quantity", line.quantity(), "unitCost", cost, "lineTotal", lineTotal));
        }
        return resolved;
    }

    private BigDecimal total(List<Map<String, Object>> resolved) {
        BigDecimal total = resolved.stream().map(line -> (BigDecimal) line.get("lineTotal"))
            .reduce(BigDecimal.ZERO.setScale(4), BigDecimal::add);
        decimal(total, false);
        return total;
    }

    private void insertItems(UUID orderId, List<Map<String, Object>> resolved) {
        for (var line : resolved) jdbc.update("""
            insert into purchasing.supplier_order_items(id, order_id, product_id, product_name, quantity, unit_cost, line_total)
            values (?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), orderId, line.get("productId"), line.get("name"), line.get("quantity"), line.get("unitCost"), line.get("lineTotal"));
    }

    private Map<UUID, BigDecimal> quantities(List<Map<String, Object>> lines) {
        Map<UUID, BigDecimal> result = new HashMap<>();
        for (var line : lines) result.merge((UUID) line.get("productId"), (BigDecimal) line.get("quantity"), BigDecimal::add);
        return result;
    }

    private static void requireHalfUnits(List<LineInput> lines) {
        for (LineInput line : lines) {
            if (line.quantity().remainder(HALF).signum() != 0) {
                throw new IllegalArgumentException("Las cantidades deben ser múltiplos de 0,5");
            }
        }
    }

    private List<Map<String, Object>> existing(String key) {
        return jdbc.queryForList("select id, order_number as number, total, status, request_fingerprint from purchasing.supplier_orders where idempotency_key = ?", key);
    }

    private OrderResult replay(Map<String, Object> row, String fingerprint) {
        if (!fingerprint.equals(row.get("request_fingerprint"))) throw new IllegalStateException("La clave del pedido ya se utilizó con otros datos");
        return new OrderResult((UUID) row.get("id"), (String) row.get("number"), (BigDecimal) row.get("total"), (String) row.get("status"));
    }

    public static void validate(OrderInput input) {
        if (input == null || input.idempotencyKey() == null
            || input.idempotencyKey().isBlank() || input.idempotencyKey().length() > 100) throw new IllegalArgumentException("Los datos del pedido son inválidos");
        validateLines(input.supplierId(), input.orderDate(), input.lines());
        // New purchases may move stock, which only accepts half units.
        requireHalfUnits(input.lines());
    }

    private static void validateLines(UUID supplierId, LocalDate orderDate, List<LineInput> lines) {
        if (supplierId == null || orderDate == null || lines == null || lines.isEmpty() || lines.size() > 1000) {
            throw new IllegalArgumentException("Los datos del pedido son inválidos");
        }
        var seen = new HashSet<UUID>();
        for (LineInput line : lines) {
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
                (input.supplierId() + "|" + input.orderDate() + lines + (input.confirms() ? "|CONFIRM" : "")).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
