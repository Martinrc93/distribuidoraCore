package com.distribuidora.order.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.payment.application.AccountLedger;
import com.distribuidora.payment.application.DeliveryDebtQuery;
import com.distribuidora.sale.application.CommissionAccrualService;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class DeliveryLifecycleService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.9999");

    public interface DeliveryPaymentCommand {
        String method();
        BigDecimal amount();
    }

    public interface DeliveryAttemptCommand {
        String result();
        String observation();
        List<? extends DeliveryPaymentCommand> payments();
        String transferReference();
        default BigDecimal previousDebtAmount() { return ZERO; }
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;
    private final OrderFulfillmentService fulfillment;
    private final CommissionAccrualService commissions;
    private final OrderCancellationService cancellations;

    @Autowired
    public DeliveryLifecycleService(JdbcTemplate jdbc, InventoryMovementService inventory, AuditService audit,
                                    CurrentUserAccess currentUser, OrderFulfillmentService fulfillment,
                                    CommissionAccrualService commissions, OrderCancellationService cancellations) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        Objects.requireNonNull(inventory, "inventory");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
        this.fulfillment = Objects.requireNonNull(fulfillment, "fulfillment");
        this.commissions = Objects.requireNonNull(commissions, "commissions");
        this.cancellations = Objects.requireNonNull(cancellations, "cancellations");
    }

    public DeliveryLifecycleService(JdbcTemplate jdbc, InventoryMovementService inventory, AuditService audit,
                                    CurrentUserAccess currentUser, OrderFulfillmentService fulfillment,
                                    CommissionAccrualService commissions) {
        this(jdbc, inventory, audit, currentUser, fulfillment, commissions,
            new OrderCancellationService(jdbc, inventory, audit, currentUser));
    }

    @Transactional
    public void recordAttempt(UUID orderId, DeliveryAttemptCommand request) {
        validateAttempt(orderId, request);
        currentUser.requireOrderAccess(orderId);
        if (previousDebtAmount(request).signum() > 0) {
            UUID saleId = jdbc.queryForObject("select id from sale.sales where order_id = ?", UUID.class, orderId);
            new DeliveryDebtQuery(jdbc, currentUser).lockSales(saleId);
        }
        Map<String, Object> lifecycle = OrderCancellationService.lockOrderAndSale(jdbc, orderId);
        OrderCancellationService.requireConfirmed(lifecycle);

        Timestamp now = Timestamp.from(Instant.now());
        DeliveryPaymentResult paymentResult = "DELIVERED".equals(request.result())
            ? collectDeliveryPayments(lifecycle, request, now)
            : new DeliveryPaymentResult(ZERO, decimal(lifecycle.get("paid")), ZERO);
        if ("DELIVERED".equals(request.result())) fulfillment.prepareLocked(orderId, lifecycle, now);

        int nextAttempt = Objects.requireNonNullElse(jdbc.queryForObject(
            "select coalesce(max(attempt_number), 0) from orders.delivery_attempts where order_id = ?",
            Integer.class, orderId), 0) + 1;
        UUID attemptId = UUID.randomUUID();
        UUID actorId = currentUser.userId();
        jdbc.update("insert into orders.delivery_attempts(id, order_id, attempt_number, result, observation, attempted_by, attempted_at) values (?, ?, ?, ?, ?, ?, ?)",
            attemptId, orderId, nextAttempt, request.result(), normalize(request.observation()), actorId, now);

        if ("DELIVERED".equals(request.result())) {
            UUID saleId = uuid(lifecycle, "sale_id");
            jdbc.update("update orders.orders set status = 'DELIVERED', delivered_at = ? where id = ? and status = 'CONFIRMED'",
                now, orderId);
            jdbc.update("update sale.sales set status = 'DELIVERED', paid = ?, delivered_at = ? where id = ? and status = 'CONFIRMED'",
                paymentResult.paid(), now, saleId);
            commissions.delivered(saleId, now);
        }
        Map<String, Object> auditDetails = new HashMap<>();
        auditDetails.put("attemptId", attemptId.toString());
        auditDetails.put("attemptNumber", nextAttempt);
        auditDetails.put("result", request.result());
        auditDetails.put("paymentsReceived", paymentResult.received());
        auditDetails.put("paidTotal", paymentResult.paid());
        auditDetails.put("previousDebtAmount", previousDebtAmount(request));
        if ("DELIVERED".equals(request.result())) {
            auditDetails.put("remainingAccountDebt", paymentResult.remainingAccountDebt());
            if (paymentResult.previousDebtApplied().signum() > 0) {
                auditDetails.put("previousDebtApplied", paymentResult.previousDebtApplied());
                auditDetails.put("previousDebtAllocations", paymentResult.previousDebtAllocations());
            }
        }
        if (normalize(request.transferReference()) != null) auditDetails.put("transferReference", normalize(request.transferReference()));
        audit.recordWithinTransaction(actorId, "DELIVERY_ATTEMPT", "ORDER", orderId.toString(), "SUCCESS", auditDetails);
    }

    private DeliveryPaymentResult collectDeliveryPayments(Map<String, Object> lifecycle,
                                                          DeliveryAttemptCommand request,
                                                          Timestamp now) {
        if (previousDebtAmount(request).signum() > 0) return collectWithPreviousDebt(lifecycle, request, now);
        List<? extends DeliveryPaymentCommand> payments = request.payments() == null
            ? List.of() : request.payments();
        BigDecimal received = payments.stream().map(DeliveryPaymentCommand::amount)
            .reduce(ZERO, BigDecimal::add).setScale(4);
        BigDecimal paidBefore = decimal(lifecycle.get("paid")).setScale(4);
        BigDecimal total = decimal(lifecycle.get("total")).setScale(4);
        UUID saleId = uuid(lifecycle, "sale_id");
        UUID accountId = uuid(lifecycle, "account_customer_id");
        AccountLedger ledger = new AccountLedger(jdbc);
        Map<String, Object> account = ledger.lockAccount(accountId);
        BigDecimal accountBalance = decimalOrZero(account.get("balance")).max(ZERO);
        BigDecimal debtBefore = decimalOrZero(ledger.saleBalance(saleId)).setScale(4);
        if (received.signum() > 0) {
            ledger.requireCollectionAllowed(accountId, currentUser.isAdmin());
            BigDecimal remainingBySaleTotal = total.subtract(paidBefore).max(ZERO);
            BigDecimal availableDebt = debtBefore.max(ZERO).min(remainingBySaleTotal).min(accountBalance);
            if (received.compareTo(availableDebt) > 0) {
                throw new IllegalStateException("El cobro supera el saldo pendiente de la venta");
            }

            String transferReference = normalize(request.transferReference());
            for (DeliveryPaymentCommand payment : payments) {
                ledger.recordPayment(accountId, saleId, payment.amount().setScale(4), payment.method(),
                    "BANK_TRANSFER".equals(payment.method()) ? transferReference : null, now);
            }
            ledger.credit(accountId, saleId, received, now);
            ledger.decreaseBalance(accountId, received);
        }
        BigDecimal paid = paidBefore.add(received).setScale(4);
        return new DeliveryPaymentResult(received, paid, debtBefore.subtract(received).setScale(4));
    }

    private DeliveryPaymentResult collectWithPreviousDebt(Map<String, Object> lifecycle,
                                                          DeliveryAttemptCommand request, Timestamp now) {
        UUID saleId = uuid(lifecycle, "sale_id");
        UUID accountId = uuid(lifecycle, "account_customer_id");
        BigDecimal selected = previousDebtAmount(request);
        AccountLedger ledger = new AccountLedger(jdbc);
        ledger.requireCollectionAllowed(accountId, currentUser.isAdmin());
        Map<String, Object> account = ledger.lockAccount(accountId);
        // Earlier debts are the account's: the same account owes every sale paid here.
        List<DeliveryDebtQuery.Debt> previous = new DeliveryDebtQuery(jdbc, currentUser).previousSales(saleId);
        BigDecimal availablePrevious = previous.stream().map(DeliveryDebtQuery.Debt::amount).reduce(ZERO, BigDecimal::add);
        if (selected.compareTo(availablePrevious) > 0) {
            throw new IllegalStateException("El importe supera la deuda anterior disponible. Actualiza el pedido e intenta nuevamente");
        }
        BigDecimal ledgerDue = decimalOrZero(ledger.saleBalance(saleId)).setScale(4);
        BigDecimal currentDue = ledgerDue.max(ZERO).min(decimal(lifecycle.get("total")).subtract(decimal(lifecycle.get("paid"))).max(ZERO));
        List<? extends DeliveryPaymentCommand> payments = request.payments() == null ? List.of() : request.payments();
        BigDecimal received = payments.stream().map(DeliveryPaymentCommand::amount).reduce(ZERO, BigDecimal::add).setScale(4);
        if (received.compareTo(currentDue.add(selected)) > 0 || received.compareTo(decimalOrZero(account.get("balance")).max(ZERO)) > 0) {
            throw new IllegalStateException("El pago supera el saldo pendiente seleccionado");
        }
        List<DeliveryDebtQuery.Debt> debts = new ArrayList<>();
        debts.add(new DeliveryDebtQuery.Debt(saleId, currentDue));
        BigDecimal previousLimit = selected;
        for (var debt : previous) {
            BigDecimal amount = debt.amount().min(previousLimit);
            debts.add(new DeliveryDebtQuery.Debt(debt.saleId(), amount));
            previousLimit = previousLimit.subtract(amount);
        }
        int debtIndex = 0;
        BigDecimal remainingDue = currentDue;
        BigDecimal currentApplied = ZERO;
        BigDecimal previousDebtApplied = ZERO;
        Map<String, BigDecimal> previousDebtAllocations = new LinkedHashMap<>();
        for (var payment : payments) {
            BigDecimal remainingPayment = payment.amount().setScale(4);
            while (remainingPayment.signum() > 0) {
                while (remainingDue.signum() == 0 && ++debtIndex < debts.size()) remainingDue = debts.get(debtIndex).amount();
                if (debtIndex >= debts.size()) throw new IllegalStateException("No hay deuda suficiente para registrar el pago");
                UUID targetSale = debts.get(debtIndex).saleId();
                BigDecimal applied = remainingPayment.min(remainingDue);
                ledger.recordPayment(accountId, targetSale, applied, payment.method(),
                    "BANK_TRANSFER".equals(payment.method()) ? normalize(request.transferReference()) : null, now);
                ledger.credit(accountId, targetSale, applied, now);
                if (targetSale.equals(saleId)) {
                    currentApplied = currentApplied.add(applied);
                } else {
                    jdbc.update("update sale.sales set paid = paid + ? where id = ?", applied, targetSale);
                    previousDebtApplied = previousDebtApplied.add(applied);
                    previousDebtAllocations.merge(targetSale.toString(), applied, BigDecimal::add);
                }
                remainingDue = remainingDue.subtract(applied);
                remainingPayment = remainingPayment.subtract(applied);
            }
        }
        if (received.signum() > 0) ledger.decreaseBalance(accountId, received);
        return new DeliveryPaymentResult(received, decimal(lifecycle.get("paid")).add(currentApplied).setScale(4),
            ledgerDue.subtract(currentApplied).setScale(4), previousDebtApplied, Map.copyOf(previousDebtAllocations));
    }

    @Transactional
    public void cancel(UUID orderId) {
        cancellations.cancel(orderId);
    }

    @Transactional
    public void reactivate(UUID orderId) {
        cancellations.reactivate(orderId);
    }

    private BigDecimal previousDebtAmount(DeliveryAttemptCommand request) {
        return request.previousDebtAmount() == null ? ZERO : request.previousDebtAmount().setScale(4);
    }

    private void validateAttempt(UUID orderId, DeliveryAttemptCommand request) {
        if (orderId == null || request == null || request.result() == null
            || (!"DELIVERED".equals(request.result()) && !"FAILED".equals(request.result()))) {
            throw new IllegalArgumentException("result es inválido");
        }
        if (request.previousDebtAmount() != null && (request.previousDebtAmount().signum() < 0
            || request.previousDebtAmount().scale() > 4 || request.previousDebtAmount().compareTo(MAX_AMOUNT) > 0)) {
            throw new IllegalArgumentException("La deuda anterior debe ser no negativa y tener hasta 4 decimales");
        }
        if ("FAILED".equals(request.result()) && previousDebtAmount(request).signum() > 0) {
            throw new IllegalArgumentException("Una entrega fallida no puede incluir deuda anterior");
        }
        BigDecimal paymentTotal = ZERO;
        boolean hasBankTransfer = false;
        if (request.payments() != null) {
            for (DeliveryPaymentCommand payment : request.payments()) {
                if (payment == null || payment.method() == null
                    || (!"CASH".equals(payment.method()) && !"BANK_TRANSFER".equals(payment.method()))
                    || payment.amount() == null || payment.amount().signum() <= 0 || payment.amount().scale() > 4
                    || payment.amount().compareTo(MAX_AMOUNT) > 0) {
                    throw new IllegalArgumentException("Los cobros de entrega deben ser CASH o BANK_TRANSFER con importe positivo de hasta 4 decimales");
                }
                paymentTotal = paymentTotal.add(payment.amount()).setScale(4);
                hasBankTransfer |= "BANK_TRANSFER".equals(payment.method());
            }
        }
        if (previousDebtAmount(request).signum() > 0 && paymentTotal.signum() == 0) {
            throw new IllegalArgumentException("Para imputar deuda anterior debe registrar un cobro positivo");
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

    private UUID uuid(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private BigDecimal decimalOrZero(Object value) {
        return value == null ? ZERO : decimal(value);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private record DeliveryPaymentResult(BigDecimal received, BigDecimal paid, BigDecimal remainingAccountDebt,
                                         BigDecimal previousDebtApplied, Map<String, BigDecimal> previousDebtAllocations) {
        private DeliveryPaymentResult(BigDecimal received, BigDecimal paid, BigDecimal remainingAccountDebt) {
            this(received, paid, remainingAccountDebt, ZERO, Map.of());
        }
    }
}
