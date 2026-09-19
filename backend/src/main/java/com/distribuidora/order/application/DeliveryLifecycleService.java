package com.distribuidora.order.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.order.api.DeliveryLifecycleDtos;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DeliveryLifecycleService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);

    private final JdbcTemplate jdbc;
    private final InventoryMovementService inventory;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;

    public DeliveryLifecycleService(JdbcTemplate jdbc, InventoryMovementService inventory, AuditService audit) {
        this(jdbc, inventory, audit, null);
    }

    @Autowired
    public DeliveryLifecycleService(JdbcTemplate jdbc, InventoryMovementService inventory, AuditService audit,
                                    CurrentUserAccess currentUser) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional
    public void recordAttempt(UUID orderId, DeliveryLifecycleDtos.DeliveryAttemptRequest request) {
        validateAttempt(orderId, request);
        if (currentUser != null) currentUser.requireOrderAccess(orderId);
        Map<String, Object> lifecycle = lockOrderAndSale(orderId);
        requireConfirmed(lifecycle);

        int nextAttempt = jdbc.queryForObject(
            "select coalesce(max(attempt_number), 0) from orders.delivery_attempts where order_id = ?",
            Integer.class, orderId) + 1;
        UUID attemptId = UUID.randomUUID();
        UUID actorId = actorId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into orders.delivery_attempts(id, order_id, attempt_number, result, observation, attempted_by, attempted_at) values (?, ?, ?, ?, ?, ?, ?)",
            attemptId, orderId, nextAttempt, request.result(), normalize(request.observation()), actorId, now);

        if ("DELIVERED".equals(request.result())) {
            UUID saleId = uuid(lifecycle, "sale_id");
            jdbc.update("update orders.orders set status = 'DELIVERED', delivered_at = ? where id = ? and status = 'CONFIRMED'",
                now, orderId);
            jdbc.update("update sale.sales set status = 'DELIVERED', delivered_at = ? where id = ? and status = 'CONFIRMED'",
                now, saleId);
        }
        audit.recordWithinTransaction(actorId, "DELIVERY_ATTEMPT", "ORDER", orderId.toString(), "SUCCESS",
            Map.of("attemptId", attemptId.toString(), "attemptNumber", nextAttempt, "result", request.result()));
    }

    @Transactional
    public void cancel(UUID orderId) {
        if (currentUser != null && !currentUser.isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException("Solo ADMIN_ALL puede cancelar ventas");
        }
        Map<String, Object> lifecycle = lockOrderAndSale(orderId);
        requireConfirmed(lifecycle);
        BigDecimal paid = decimal(lifecycle.get("paid"));
        if (paid.signum() != 0) {
            throw new IllegalStateException("Una venta pagada no puede cancelarse");
        }

        UUID saleId = uuid(lifecycle, "sale_id");
        UUID customerId = uuid(lifecycle, "customer_id");
        Map<String, Object> customer = jdbc.queryForMap(
            "select id, balance from customer.customers where id = ? for update", customerId);
        reversePersistedSaleMovements(orderId, saleId);

        BigDecimal credit = decimal(lifecycle.get("total")).subtract(paid).setScale(4);
        Timestamp now = Timestamp.from(Instant.now());
        if (credit.signum() > 0) {
            jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'CREDIT', ?, ?)",
                UUID.randomUUID(), customerId, saleId, credit, now);
            jdbc.update("update customer.customers set balance = balance - ? where id = ?", credit, customerId);
        }
        jdbc.update("update orders.orders set status = 'CANCELLED', cancelled_at = ? where id = ? and status = 'CONFIRMED'",
            now, orderId);
        jdbc.update("update sale.sales set status = 'CANCELLED', cancelled_at = ? where id = ? and status = 'CONFIRMED'",
            now, saleId);
        audit.recordWithinTransaction(actorId(), "ORDER_CANCEL", "ORDER", orderId.toString(), "SUCCESS",
            Map.of("saleId", saleId.toString(), "credit", credit));
    }

    private void reversePersistedSaleMovements(UUID orderId, UUID saleId) {
        // Confirmation historically used orderId, while demo/legacy rows may use saleId.
        // Read both references; new cancellation rows use orderId as the canonical reference.
        Object[] references = {orderId, saleId};
        List<Map<String, Object>> cancellations = jdbc.queryForList(
            "select product_id, quantity from inventory.stock_movements "
                + "where movement_type = 'SALE_CANCELLATION' and reference_id in (?, ?)", references);
        Map<UUID, List<BigDecimal>> existing = new HashMap<>();
        cancellations.forEach(row -> existing.computeIfAbsent(uuid(row, "product_id"), ignored -> new ArrayList<>())
            .add(decimal(row.get("quantity")).abs()));

        List<Map<String, Object>> sales = jdbc.queryForList(
            "select id, product_id, quantity from inventory.stock_movements "
                + "where movement_type = 'SALE' and reference_id in (?, ?) order by created_at, id", references);
        for (Map<String, Object> movement : sales) {
            UUID productId = uuid(movement, "product_id");
            BigDecimal originalQuantity = decimal(movement.get("quantity"));
            List<BigDecimal> reversed = existing.getOrDefault(productId, List.of());
            int matchingCancellation = -1;
            for (int index = 0; index < reversed.size(); index++) {
                if (reversed.get(index).compareTo(originalQuantity.abs()) == 0) {
                    matchingCancellation = index;
                    break;
                }
            }
            if (matchingCancellation >= 0) {
                reversed.remove(matchingCancellation);
                continue;
            }
            inventory.apply(productId, originalQuantity.negate(), "SALE_CANCELLATION", orderId, "Sale cancellation");
        }
    }

    private Map<String, Object> lockOrderAndSale(UUID orderId) {
        try {
            return jdbc.queryForMap("select o.id as order_id, o.status as order_status, s.id as sale_id, s.status as sale_status, s.customer_id, s.total, s.paid "
                + "from orders.orders o join sale.sales s on s.order_id = o.id where o.id = ? for update", orderId);
        } catch (EmptyResultDataAccessException exception) {
            throw exception;
        }
    }

    private void requireConfirmed(Map<String, Object> lifecycle) {
        if (!"CONFIRMED".equals(lifecycle.get("order_status")) || !"CONFIRMED".equals(lifecycle.get("sale_status"))) {
            throw new IllegalStateException("El pedido o la venta no pueden modificarse");
        }
    }

    private void validateAttempt(UUID orderId, DeliveryLifecycleDtos.DeliveryAttemptRequest request) {
        if (orderId == null || request == null || request.result() == null
            || (!"DELIVERED".equals(request.result()) && !"FAILED".equals(request.result()))) {
            throw new IllegalArgumentException("result es inválido");
        }
        if ("FAILED".equals(request.result()) && (request.observation() == null || request.observation().isBlank())) {
            throw new IllegalArgumentException("observation es obligatorio para intentos fallidos");
        }
    }

    private UUID actorId() {
        try {
            return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo identificar al usuario actual");
        }
    }

    private UUID uuid(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }
}
