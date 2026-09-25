package com.distribuidora.order.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DeliveryLifecycleService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);

    public interface DeliveryPaymentCommand {
        String method();
        BigDecimal amount();
    }

    public interface DeliveryAttemptCommand {
        String result();
        String observation();
        List<? extends DeliveryPaymentCommand> payments();
        String transferReference();
    }

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
    public void recordAttempt(UUID orderId, DeliveryAttemptCommand request) {
        validateAttempt(orderId, request);
        if (currentUser != null) currentUser.requireOrderAccess(orderId);
        Map<String, Object> lifecycle = lockOrderAndSale(orderId);
        requireConfirmed(lifecycle);

        Timestamp now = Timestamp.from(Instant.now());
        DeliveryPaymentResult paymentResult = "DELIVERED".equals(request.result())
            ? collectDeliveryPayments(lifecycle, request, now)
            : new DeliveryPaymentResult(ZERO, decimal(lifecycle.get("paid")), ZERO);

        int nextAttempt = jdbc.queryForObject(
            "select coalesce(max(attempt_number), 0) from orders.delivery_attempts where order_id = ?",
            Integer.class, orderId) + 1;
        UUID attemptId = UUID.randomUUID();
        UUID actorId = actorId();
        jdbc.update("insert into orders.delivery_attempts(id, order_id, attempt_number, result, observation, attempted_by, attempted_at) values (?, ?, ?, ?, ?, ?, ?)",
            attemptId, orderId, nextAttempt, request.result(), normalize(request.observation()), actorId, now);

        if ("DELIVERED".equals(request.result())) {
            UUID saleId = uuid(lifecycle, "sale_id");
            jdbc.update("update orders.orders set status = 'DELIVERED', delivered_at = ? where id = ? and status = 'CONFIRMED'",
                now, orderId);
            jdbc.update("update sale.sales set status = 'DELIVERED', paid = ?, delivered_at = ? where id = ? and status = 'CONFIRMED'",
                paymentResult.paid(), now, saleId);
        }
        Map<String, Object> auditDetails = new java.util.HashMap<>();
        auditDetails.put("attemptId", attemptId.toString());
        auditDetails.put("attemptNumber", nextAttempt);
        auditDetails.put("result", request.result());
        auditDetails.put("paymentsReceived", paymentResult.received());
        auditDetails.put("paidTotal", paymentResult.paid());
        if ("DELIVERED".equals(request.result())) {
            auditDetails.put("remainingAccountDebt", paymentResult.remainingAccountDebt());
        }
        if (normalize(request.transferReference()) != null) auditDetails.put("transferReference", normalize(request.transferReference()));
        audit.recordWithinTransaction(actorId, "DELIVERY_ATTEMPT", "ORDER", orderId.toString(), "SUCCESS", auditDetails);
    }

    private DeliveryPaymentResult collectDeliveryPayments(Map<String, Object> lifecycle,
                                                           DeliveryAttemptCommand request,
                                                           Timestamp now) {
        List<? extends DeliveryPaymentCommand> payments = request.payments() == null
            ? List.of() : request.payments();
        BigDecimal received = payments.stream().map(DeliveryPaymentCommand::amount)
            .reduce(ZERO, BigDecimal::add).setScale(4);
        BigDecimal paidBefore = decimal(lifecycle.get("paid")).setScale(4);
        BigDecimal total = decimal(lifecycle.get("total")).setScale(4);
        UUID saleId = uuid(lifecycle, "sale_id");
        UUID customerId = uuid(lifecycle, "customer_id");
        jdbc.queryForMap("select id, balance from customer.customers where id = ? for update", customerId);
        BigDecimal debtBefore = jdbc.queryForObject(
            "select coalesce(sum(case when entry_type = 'DEBIT' then amount else -amount end), 0) "
                + "from customer.account_ledger where sale_id = ?", BigDecimal.class, saleId).setScale(4);
        if (received.signum() > 0) {
            BigDecimal remainingBySaleTotal = total.subtract(paidBefore).max(ZERO);
            BigDecimal availableDebt = debtBefore.max(ZERO).min(remainingBySaleTotal);
            if (received.compareTo(availableDebt) > 0) {
                throw new IllegalStateException("El cobro supera el saldo pendiente de la venta");
            }

            String transferReference = normalize(request.transferReference());
            for (DeliveryPaymentCommand payment : payments) {
                jdbc.update("insert into payment.payments(id, sale_id, customer_id, amount, method, transfer_reference, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), saleId, customerId, payment.amount().setScale(4), payment.method(),
                    "BANK_TRANSFER".equals(payment.method()) ? transferReference : null, now);
            }
            jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'CREDIT', ?, ?)",
                UUID.randomUUID(), customerId, saleId, received, now);
            jdbc.update("update customer.customers set balance = balance - ? where id = ?", received, customerId);
        }
        BigDecimal paid = paidBefore.add(received).setScale(4);
        return new DeliveryPaymentResult(received, paid, debtBefore.subtract(received).setScale(4));
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
        // Reverse the net stock effect so previous order edits cannot cause over-restoration.
        List<Map<String, Object>> netMovements = jdbc.queryForList(
            "select depot_id, product_id, sum(quantity) as net_quantity from inventory.stock_movements "
                + "where movement_type in ('SALE', 'SALE_CANCELLATION') and reference_id in (?, ?) "
                + "group by depot_id, product_id having sum(quantity) <> 0 order by depot_id, product_id", new Object[]{orderId, saleId});
        for (Map<String, Object> movement : netMovements) {
            UUID depotId = uuid(movement, "depot_id");
            UUID productId = uuid(movement, "product_id");
            BigDecimal netQuantity = decimal(movement.get("net_quantity"));
            inventory.apply(depotId, productId, netQuantity.negate(), "SALE_CANCELLATION", orderId, "Sale cancellation");
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

    private void validateAttempt(UUID orderId, DeliveryAttemptCommand request) {
        if (orderId == null || request == null || request.result() == null
            || (!"DELIVERED".equals(request.result()) && !"FAILED".equals(request.result()))) {
            throw new IllegalArgumentException("result es inválido");
        }
        BigDecimal paymentTotal = ZERO;
        boolean hasBankTransfer = false;
        if (request.payments() != null) {
            for (DeliveryPaymentCommand payment : request.payments()) {
                if (payment == null || payment.method() == null
                    || (!"CASH".equals(payment.method()) && !"BANK_TRANSFER".equals(payment.method()))
                    || payment.amount() == null || payment.amount().signum() <= 0 || payment.amount().scale() > 4) {
                    throw new IllegalArgumentException("Los cobros de entrega deben ser CASH o BANK_TRANSFER con importe positivo de hasta 4 decimales");
                }
                paymentTotal = paymentTotal.add(payment.amount()).setScale(4);
                hasBankTransfer |= "BANK_TRANSFER".equals(payment.method());
            }
        }
        String transferReference = normalize(request.transferReference());
        if (transferReference != null && transferReference.length() > 100) {
            throw new IllegalArgumentException("transferReference no puede superar 100 caracteres");
        }
        if ("FAILED".equals(request.result()) && (paymentTotal.signum() > 0 || transferReference != null)) {
            throw new IllegalArgumentException("No se pueden registrar cobros para una entrega fallida");
        }
        if (transferReference != null && !hasBankTransfer) {
            throw new IllegalArgumentException("transferReference requiere al menos un cobro BANK_TRANSFER");
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

    private record DeliveryPaymentResult(BigDecimal received, BigDecimal paid, BigDecimal remainingAccountDebt) { }
}
