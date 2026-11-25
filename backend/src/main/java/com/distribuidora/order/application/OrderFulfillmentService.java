package com.distribuidora.order.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;

@Service
public class OrderFulfillmentService {
    private final JdbcTemplate jdbc;
    private final InventoryMovementService inventory;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;

    public OrderFulfillmentService(JdbcTemplate jdbc, InventoryMovementService inventory,
                                   AuditService audit, CurrentUserAccess currentUser) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
    }

    @Transactional
    public void prepare(UUID orderId) {
        requireAuthority("ORDER_PREPARE");
        currentUser.requireOrderAccess(orderId);
        Map<String, Object> order = jdbc.queryForMap("""
            select o.status as order_status, s.status as sale_status, o.fulfillment_status
            from orders.orders o join sale.sales s on s.order_id = o.id
            where o.id = ? for update of o, s
            """, orderId);
        prepareLocked(orderId, order, Timestamp.from(Instant.now()));
    }

    // Called by successful delivery under the same order/sale locks and transaction.
    // SALE_DELIVER authorizes the implicit preparation; explicit preparation uses ORDER_PREPARE.
    void prepareLocked(UUID orderId, Map<String, Object> order, Timestamp now) {
        if (!"CONFIRMED".equals(order.get("order_status")) || !"CONFIRMED".equals(order.get("sale_status"))) {
            throw new IllegalStateException("Solo se pueden preparar pedidos y ventas confirmados");
        }
        if ("PREPARED".equals(order.get("fulfillment_status"))) return;
        if (!"PENDING".equals(order.get("fulfillment_status"))) {
            throw new IllegalStateException("El estado de preparación no es válido");
        }
        UUID actorId = currentUser.userId();
        var lines = jdbc.queryForList("""
            select product_id, sum(quantity) as quantity from orders.order_items
            where order_id = ? group by product_id order by product_id
            """, orderId);
        if (lines.isEmpty()) throw new IllegalStateException("El pedido no tiene productos para preparar");
        for (var line : lines) {
            inventory.applyConfirmedOrderSale((UUID) line.get("product_id"), ((BigDecimal) line.get("quantity")).negate(),
                orderId, "Order preparation");
        }
        jdbc.update("""
            update orders.orders set fulfillment_status = 'PREPARED', prepared_at = ?, prepared_by_user_id = ?
            where id = ?
            """, now, actorId, orderId);
        audit.recordWithinTransaction(actorId, "ORDER_PREPARE", "ORDER", orderId.toString(), "SUCCESS",
            Map.of("stockProducts", lines.size()));
    }

    @Transactional
    public void review(UUID orderId) {
        requireExplicitAuthority("ORDER_REVIEW");
        if (!currentUser.isAdmin()) throw new AccessDeniedException("La revisión requiere un administrador");
        Map<String, Object> order = jdbc.queryForMap(
            "select created_by_admin, reviewed_at from orders.orders where id = ? for update", orderId);
        if (!Boolean.FALSE.equals(order.get("created_by_admin"))) {
            throw new IllegalStateException("Solo requieren revisión los pedidos creados por vendedores");
        }
        if (order.get("reviewed_at") != null) return;
        UUID actorId = currentUser.userId();
        jdbc.update("update orders.orders set reviewed_at = ?, reviewed_by_user_id = ? where id = ?",
            Timestamp.from(Instant.now()), actorId, orderId);
        audit.recordWithinTransaction(actorId, "ORDER_REVIEW", "ORDER", orderId.toString(), "SUCCESS", Map.of());
    }

    private void requireAuthority(String code) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> code.equals(a.getAuthority()) || "ADMIN_ALL".equals(a.getAuthority()))) {
            throw new AccessDeniedException("Falta el permiso " + code);
        }
    }

    private void requireExplicitAuthority(String code) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> code.equals(a.getAuthority()))) {
            throw new AccessDeniedException("Falta el permiso " + code);
        }
    }

}
