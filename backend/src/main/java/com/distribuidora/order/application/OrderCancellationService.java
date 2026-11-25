package com.distribuidora.order.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.payment.application.AccountLedger;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.security.PermissionAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class OrderCancellationService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);

    private final JdbcTemplate jdbc;
    private final InventoryMovementService inventory;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;

    public OrderCancellationService(JdbcTemplate jdbc, InventoryMovementService inventory,
                                    AuditService audit, CurrentUserAccess currentUser) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
    }

    @Transactional
    public void cancel(UUID orderId) {
        PermissionAccess.require("ORDER_CANCEL");
        Map<String, Object> lifecycle = lockOrderAndSale(jdbc, orderId);
        currentUser.requireOrderAccess(orderId);
        requireConfirmed(lifecycle);
        BigDecimal paid = decimal(lifecycle.get("paid"));
        if (paid.signum() != 0) {
            throw new IllegalStateException("Una venta pagada no puede cancelarse");
        }

        UUID saleId = uuid(lifecycle, "sale_id");
        UUID accountId = uuid(lifecycle, "account_customer_id");
        AccountLedger ledger = new AccountLedger(jdbc);
        Map<String, Object> account = ledger.lockAccount(accountId);
        reversePersistedSaleMovements(orderId, saleId);

        BigDecimal saleDebt = decimalOrZero(ledger.saleBalance(saleId)).max(ZERO);
        BigDecimal accountBalance = decimalOrZero(account.get("balance")).max(ZERO);
        BigDecimal credit = decimal(lifecycle.get("total")).subtract(paid).max(ZERO)
            .min(saleDebt).min(accountBalance).setScale(4);
        Timestamp now = Timestamp.from(Instant.now());
        if (credit.signum() > 0) {
            ledger.credit(accountId, saleId, credit, now);
            ledger.decreaseBalance(accountId, credit);
        }
        jdbc.update("update orders.orders set status = 'CANCELLED', cancelled_at = ?, fulfillment_status = 'PENDING', prepared_at = null, prepared_by_user_id = null, legacy_fulfillment = false where id = ? and status = 'CONFIRMED'",
            now, orderId);
        jdbc.update("update sale.sales set status = 'CANCELLED', cancelled_at = ? where id = ? and status = 'CONFIRMED'",
            now, saleId);
        audit.recordWithinTransaction(currentUser.userId(), "ORDER_CANCEL", "ORDER", orderId.toString(), "SUCCESS",
            Map.of("saleId", saleId.toString(), "credit", credit));
    }

    @Transactional
    public void reactivate(UUID orderId) {
        PermissionAccess.require("ORDER_REACTIVATE");
        Map<String, Object> lifecycle = lockOrderAndSale(jdbc, orderId);
        currentUser.requireOrderAccess(orderId);
        if (!"CANCELLED".equals(lifecycle.get("order_status")) || !"CANCELLED".equals(lifecycle.get("sale_status"))) {
            throw new IllegalStateException("Solo se pueden reactivar pedidos y ventas cancelados");
        }
        if (decimal(lifecycle.get("paid")).signum() != 0) {
            throw new IllegalStateException("No se puede reactivar una venta que tiene pagos registrados");
        }

        UUID saleId = uuid(lifecycle, "sale_id");
        UUID customerId = uuid(lifecycle, "customer_id");
        UUID accountId = uuid(lifecycle, "account_customer_id");
        Integer paymentCount = jdbc.queryForObject(
            "select count(*) from payment.payments where sale_id = ?", Integer.class, saleId);
        if (paymentCount != null && paymentCount > 0) {
            throw new IllegalStateException("No se puede reactivar una venta que tiene pagos registrados");
        }
        List<Map<String, Object>> lockedAccounts = accountId.equals(customerId)
            ? jdbc.queryForList("select id, status, balance from customer.customers where id = ? for update", customerId)
            : jdbc.queryForList("select id, status, balance from customer.customers where id in (?, ?) order by id for update",
                customerId, accountId);
        Map<UUID, String> statuses = new HashMap<>();
        for (Map<String, Object> row : lockedAccounts) {
            statuses.put(uuid(row, "id"), String.valueOf(row.get("status")));
        }
        if (!"ACTIVE".equals(statuses.get(customerId))) {
            throw new IllegalStateException("El cliente no está activo");
        }
        if (!"ACTIVE".equals(statuses.get(accountId))) {
            throw new IllegalStateException("La cuenta del revendedor no está activa");
        }
        AccountLedger ledger = new AccountLedger(jdbc);

        BigDecimal total = decimal(lifecycle.get("total")).setScale(4);
        Timestamp now = Timestamp.from(Instant.now());
        if (total.signum() > 0) {
            // The debt returns to the account that owed the sale when it was confirmed.
            ledger.debit(accountId, saleId, total, now);
            ledger.increaseBalance(accountId, total);
        }
        jdbc.update("update orders.orders set status = 'CONFIRMED', cancelled_at = null where id = ? and status = 'CANCELLED'", orderId);
        jdbc.update("update sale.sales set status = 'CONFIRMED', cancelled_at = null where id = ? and status = 'CANCELLED'", saleId);
        audit.recordWithinTransaction(currentUser.userId(), "ORDER_REACTIVATE", "ORDER", orderId.toString(), "SUCCESS",
            Map.of("saleId", saleId.toString(), "total", total, "fulfillmentStatus", "PENDING"));
    }

    private void reversePersistedSaleMovements(UUID orderId, UUID saleId) {
        List<Map<String, Object>> netMovements = jdbc.queryForList(
            "select product_id, sum(quantity) as net_quantity from inventory.stock_movements "
                + "where movement_type in ('SALE', 'SALE_CANCELLATION') and reference_id in (?, ?) "
                + "group by product_id having sum(quantity) <> 0 order by product_id", orderId, saleId);
        for (Map<String, Object> movement : netMovements) {
            UUID productId = uuid(movement, "product_id");
            BigDecimal netQuantity = decimal(movement.get("net_quantity"));
            inventory.apply(productId, netQuantity.negate(), "SALE_CANCELLATION", orderId, "Sale cancellation");
        }
    }

    static Map<String, Object> lockOrderAndSale(JdbcTemplate jdbc, UUID orderId) {
        return jdbc.queryForMap("select o.id as order_id, o.status as order_status, o.fulfillment_status, s.id as sale_id, s.status as sale_status, s.customer_id, s.account_customer_id, s.total, s.paid "
            + "from orders.orders o join sale.sales s on s.order_id = o.id where o.id = ? for update of o, s", orderId);
    }

    static void requireConfirmed(Map<String, Object> lifecycle) {
        if (!"CONFIRMED".equals(lifecycle.get("order_status")) || !"CONFIRMED".equals(lifecycle.get("sale_status"))) {
            throw new IllegalStateException("El pedido o la venta no pueden modificarse");
        }
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private static BigDecimal decimalOrZero(Object value) {
        return value == null ? ZERO : decimal(value);
    }
}
