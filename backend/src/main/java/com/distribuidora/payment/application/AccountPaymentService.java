package com.distribuidora.payment.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.security.PermissionAccess;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;

@Service
public class AccountPaymentService {
    public interface PaymentCommand {
        BigDecimal amount();
        String method();
        String transferReference();
        UUID saleId();
    }

    public record AllocationResult(UUID saleId, String saleNumber, UUID paymentId, BigDecimal amount) { }

    public record PaymentResult(UUID customerId, BigDecimal received, BigDecimal balanceBefore,
                                BigDecimal balanceAfter, String allocationMode, List<AllocationResult> allocations) {
        public PaymentResult { allocations = List.copyOf(allocations); }
    }

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;

    public AccountPaymentService(JdbcTemplate jdbc, AuditService audit, CurrentUserAccess currentUser) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
    }

    @Transactional
    public PaymentResult settle(UUID customerId, List<? extends PaymentCommand> payments) {
        return settle(customerId, payments, false);
    }

    /**
     * Applies one or more payments to the customer account by FIFO in a single transaction.
     * Without {@code allowPartial} the payments must match the full balance; with it they may cover part of it.
     */
    @Transactional
    public PaymentResult settle(UUID customerId, List<? extends PaymentCommand> payments, boolean allowPartial) {
        PermissionAccess.require("ACCOUNT_SETTLE");
        currentUser.requireCustomerAccess(customerId);
        AccountLedger ledger = new AccountLedger(jdbc);
        ledger.requireCollectionAllowed(customerId, currentUser.isAdmin());
        if (payments == null || payments.isEmpty() || payments.size() > 100) {
            throw new IllegalArgumentException("Se requiere al menos un pago y se admiten hasta 100");
        }
        for (PaymentCommand payment : payments) {
            validate(customerId, payment);
            if (payment.saleId() != null) throw new IllegalArgumentException("El saldo se imputa por FIFO");
            if ("BANK_TRANSFER".equals(payment.method())
                && (normalize(payment.transferReference()) == null || normalize(payment.transferReference()).isEmpty())) {
                throw new IllegalArgumentException("Ingresa el número de transferencia");
            }
        }
        // Single-pass canonical lock order: open orders -> sales, then the customer account row.
        List<Map<String, Object>> candidates = lockCandidateSales(customerId, null);
        Map<String, Object> customer = ledger.lockAccount(customerId);
        currentUser.requireCustomerAccess(customerId);
        BigDecimal balanceBefore = decimal(customer.get("balance")).setScale(4);
        BigDecimal ledgerBalance = ledgerBalance(customerId);
        BigDecimal globallyAvailable = balanceBefore.max(ZERO).min(ledgerBalance.max(ZERO));
        BigDecimal visibleBalanceBefore = currentUser.isAdmin() ? balanceBefore : new CustomerAccountBalanceQuery(jdbc, currentUser).balance(customerId);
        BigDecimal total = payments.stream().map(PaymentCommand::amount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(4);
        if (visibleBalanceBefore.signum() <= 0) {
            throw new IllegalStateException("El cliente no tiene saldo pendiente.");
        }
        if (allowPartial ? total.compareTo(visibleBalanceBefore) > 0 : total.compareTo(visibleBalanceBefore) != 0) {
            throw new IllegalStateException(allowPartial
                ? "El saldo cambió o los pagos superan la deuda. Revisa el saldo y los importes."
                : "El saldo cambió o los pagos no cubren la deuda completa. Revisa el saldo y los importes.");
        }
        if (total.compareTo(globallyAvailable) > 0) {
            throw new IllegalStateException("El cobro supera la deuda vigente del cliente");
        }
        List<SaleDue> dues = computeDues(candidates);
        List<AllocationResult> allocations = applyBatchAllocations(customerId, payments, dues, ledger, total);
        return new PaymentResult(customerId, total, visibleBalanceBefore,
            visibleBalanceBefore.subtract(total).setScale(4), "FIFO", allocations);
    }

    /** Applies a payment to the account of {@code customerId}: the sales it owes, whoever received them. */
    @Transactional
    public PaymentResult apply(UUID customerId, PaymentCommand request) {
        PermissionAccess.require("SALE_PAYMENT");
        return applyInternal(customerId, request);
    }

    /**
     * Locks candidate open sales for an account in canonical order (orders -> sales) before callers
     * acquire customer row locks.
     */
    public List<Map<String, Object>> lockCandidateSalesForAccount(UUID accountId) {
        if (currentUser != null) currentUser.requireCustomerAccess(accountId);
        return lockCandidateSales(accountId, null);
    }

    /**
     * Applies FIFO previous-debt payments inside an outer transaction that already holds canonical
     * locks on candidate sales ({@code orders -> sales}) and on {@code customer.customers} (UUID asc).
     */
    public BigDecimal applyPreviousDebtPaymentsWithLockedContext(UUID accountId,
                                                                  List<? extends PaymentCommand> payments,
                                                                  List<Map<String, Object>> lockedCandidates,
                                                                  Map<String, Object> lockedAccount) {
        PermissionAccess.require("SALE_PAYMENT");
        if (payments == null || payments.isEmpty()) return ZERO;
        for (PaymentCommand payment : payments) {
            validate(accountId, payment);
            if (payment.saleId() != null) {
                throw new IllegalArgumentException("El saldo se imputa por FIFO");
            }
        }
        if (currentUser != null) currentUser.requireCustomerAccess(accountId);
        AccountLedger ledger = new AccountLedger(jdbc);
        boolean isAdmin = currentUser == null || currentUser.isAdmin();
        ledger.requireCollectionAllowed(accountId, isAdmin);
        BigDecimal balanceBefore = decimal(lockedAccount.get("balance")).setScale(4);
        BigDecimal ledgerBalance = ledgerBalance(accountId);
        BigDecimal globallyAvailable = balanceBefore.max(ZERO).min(ledgerBalance.max(ZERO));
        BigDecimal visibleBalanceBefore = isAdmin
            ? balanceBefore
            : new CustomerAccountBalanceQuery(jdbc, currentUser).balance(accountId);
        BigDecimal total = payments.stream().map(PaymentCommand::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(4);
        if (total.compareTo(globallyAvailable) > 0 || total.compareTo(visibleBalanceBefore) > 0) {
            throw new IllegalStateException("El cobro supera la deuda vigente del cliente");
        }
        List<SaleDue> dues = computeDues(lockedCandidates);
        applyBatchAllocations(accountId, payments, dues, ledger, total);
        return total;
    }

    private PaymentResult applyInternal(UUID customerId, PaymentCommand request) {
        validate(customerId, request);
        currentUser.requireCustomerAccess(customerId);
        AccountLedger ledger = new AccountLedger(jdbc);
        ledger.requireCollectionAllowed(customerId, currentUser.isAdmin());

        // Lock sale rows before the account so delivery, editing and payment allocation use one lock order.
        List<Map<String, Object>> candidates = lockCandidateSales(customerId, request.saleId());
        Map<String, Object> customer = ledger.lockAccount(customerId);
        currentUser.requireCustomerAccess(customerId);
        BigDecimal balanceBefore = decimal(customer.get("balance")).setScale(4);
        BigDecimal ledgerBalance = ledgerBalance(customerId);
        BigDecimal globallyAvailable = balanceBefore.max(ZERO).min(ledgerBalance.max(ZERO));
        BigDecimal visibleBalanceBefore = currentUser.isAdmin() ? balanceBefore : new CustomerAccountBalanceQuery(jdbc, currentUser).balance(customerId);
        BigDecimal amount = request.amount().setScale(4);
        if (amount.compareTo(globallyAvailable) > 0) {
            throw new IllegalStateException("El cobro supera la deuda vigente del cliente");
        }

        List<SaleDue> dues = computeDues(candidates);
        List<AllocationResult> allocations = allocateSinglePayment(
            customerId, request, dues, ledger, Timestamp.from(Instant.now()));
        BigDecimal balanceAfter = balanceBefore.subtract(amount).setScale(4);
        ledger.decreaseBalance(customerId, amount);
        String allocationMode = request.saleId() == null ? "FIFO" : "SPECIFIC";

        return new PaymentResult(customerId, amount, visibleBalanceBefore,
            currentUser.isAdmin() ? balanceAfter : visibleBalanceBefore.subtract(amount).setScale(4), allocationMode, allocations);
    }

    private List<AllocationResult> applyBatchAllocations(UUID accountId,
                                                         List<? extends PaymentCommand> payments,
                                                         List<SaleDue> dues,
                                                         AccountLedger ledger,
                                                         BigDecimal total) {
        Timestamp now = Timestamp.from(Instant.now());
        List<AllocationResult> allocations = new ArrayList<>();
        for (PaymentCommand payment : payments) {
            allocations.addAll(allocateSinglePayment(accountId, payment, dues, ledger, now));
        }
        ledger.decreaseBalance(accountId, total);
        return allocations;
    }

    private List<AllocationResult> allocateSinglePayment(UUID accountId,
                                                         PaymentCommand request,
                                                         List<SaleDue> dues,
                                                         AccountLedger ledger,
                                                         Timestamp now) {
        BigDecimal amount = request.amount().setScale(4);
        List<AllocationResult> allocations = new ArrayList<>();
        BigDecimal remaining = amount;
        String transferReference = normalize(request.transferReference());
        for (SaleDue saleDue : dues) {
            if (remaining.signum() == 0) break;
            if (saleDue.due().signum() == 0) continue;
            BigDecimal applied = remaining.min(saleDue.due()).setScale(4);
            UUID paymentId = ledger.recordPayment(accountId, saleDue.saleId(), applied, request.method(),
                "BANK_TRANSFER".equals(request.method()) ? transferReference : null, now);
            ledger.credit(accountId, saleDue.saleId(), applied, now);
            jdbc.update("update sale.sales set paid = paid + ? where id = ?", applied, saleDue.saleId());
            saleDue.subtractDue(applied);
            allocations.add(new AllocationResult(saleDue.saleId(), saleDue.saleNumber(), paymentId, applied));
            remaining = remaining.subtract(applied).setScale(4);
        }
        if (remaining.signum() != 0) {
            throw new IllegalStateException("No hay deuda abierta suficiente para imputar el cobro");
        }
        String allocationMode = request.saleId() == null ? "FIFO" : "SPECIFIC";
        List<Map<String, Object>> auditedAllocations = allocations.stream()
            .map(allocation -> Map.<String, Object>of("saleId", allocation.saleId().toString(), "amount", allocation.amount()))
            .toList();
        Map<String, Object> details = new HashMap<>();
        details.put("amount", amount);
        details.put("method", request.method());
        details.put("allocationMode", allocationMode);
        details.put("allocations", auditedAllocations);
        if (transferReference != null) details.put("transferReference", transferReference);
        audit.recordWithinTransaction(currentUser.userId(), "ACCOUNT_PAYMENT_APPLY", "CUSTOMER", accountId.toString(), "SUCCESS", details);
        return allocations;
    }

    private BigDecimal ledgerBalance(UUID customerId) {
        BigDecimal value = jdbc.queryForObject(
            "select coalesce(sum(case when entry_type = 'DEBIT' then amount else -amount end), 0) "
                + "from customer.account_ledger where customer_id = ?", BigDecimal.class, customerId);
        return value == null ? ZERO : value.setScale(4);
    }

    private List<SaleDue> computeDues(List<Map<String, Object>> candidates) {
        if (candidates.isEmpty()) return new ArrayList<>();
        List<UUID> saleIds = candidates.stream().map(candidate -> uuid(candidate.get("id"))).toList();
        String placeholders = String.join(",", Collections.nCopies(saleIds.size(), "?"));
        Map<UUID, BigDecimal> ledgerBySale = new HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
            "select sale_id, coalesce(sum(case when entry_type = 'DEBIT' then amount else -amount end), 0) as balance "
                + "from customer.account_ledger where sale_id in (" + placeholders + ") group by sale_id",
            saleIds.toArray())) {
            ledgerBySale.put(uuid(row.get("sale_id")), decimal(row.get("balance")).setScale(4));
        }
        List<SaleDue> dues = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            UUID saleId = uuid(candidate.get("id"));
            BigDecimal ledgerDue = ledgerBySale.getOrDefault(saleId, ZERO);
            BigDecimal unpaidBySale = decimal(candidate.get("total"))
                .subtract(decimal(candidate.get("paid"))).max(ZERO).setScale(4);
            BigDecimal due = ledgerDue.max(ZERO).min(unpaidBySale);
            if (due.signum() > 0) {
                dues.add(new SaleDue(saleId, String.valueOf(candidate.get("sale_number")), due));
            }
        }
        return dues;
    }

    /** Open sales owed by the account; locks orders before sales, with seller scope fallback to recipient. */
    private List<Map<String, Object>> lockCandidateSales(UUID customerId, UUID saleId) {
        if (saleId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList("select s.id, s.sale_number, s.status, s.total, s.paid "
                + "from orders.orders o join sale.sales s on s.order_id = o.id where s.id = ? and s.account_customer_id = ? for update of o, s", saleId, customerId);
            if (rows.isEmpty()) throw new EmptyResultDataAccessException(1);
            UUID orderId = jdbc.queryForObject("select order_id from sale.sales where id = ?", UUID.class, saleId);
            currentUser.requireOrderAccess(orderId);
            if (!"CONFIRMED".equals(rows.get(0).get("status")) && !"DELIVERED".equals(rows.get(0).get("status"))) {
                throw new IllegalStateException("Solo se pueden cobrar ventas confirmadas o entregadas");
            }
            return rows;
        }
        String source = "from orders.orders o join sale.sales s on s.order_id = o.id "
            + "join customer.customers c on c.id = s.customer_id where s.account_customer_id = ? "
            + "and s.status in ('CONFIRMED', 'DELIVERED') and s.paid < s.total ";
        List<Object> parameters = new ArrayList<>(List.of(customerId));
        if (!currentUser.isAdmin()) {
            UUID sellerId = currentUser.requireSellerProfile();
            source += "and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?)) ";
            parameters.add(sellerId);
            parameters.add(sellerId);
        }
        return jdbc.queryForList("select s.id, s.sale_number, s.status, s.total, s.paid, s.created_at " + source
            + "order by o.id for update of o, s", parameters.toArray())
            .stream()
            .sorted(java.util.Comparator
                .comparing((Map<String, Object> row) -> toInstant(row.get("created_at")), java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(row -> uuid(row.get("id")).toString()))
            .toList();
    }

    private Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.util.Date date) return date.toInstant();
        return Instant.parse(String.valueOf(value));
    }

    private void validate(UUID customerId, PaymentCommand request) {
        if (customerId == null || request == null || request.amount() == null || request.amount().signum() <= 0
            || request.amount().scale() > 4
            || (!"CASH".equals(request.method()) && !"BANK_TRANSFER".equals(request.method()))) {
            throw new IllegalArgumentException("El cliente, método o importe del pago es inválido");
        }
        String transferReference = normalize(request.transferReference());
        if (transferReference != null && transferReference.length() > 100) {
            throw new IllegalArgumentException("transferReference no puede superar 100 caracteres");
        }
        if (transferReference != null && !"BANK_TRANSFER".equals(request.method())) {
            throw new IllegalArgumentException("transferReference requiere method BANK_TRANSFER");
        }
    }


    private UUID uuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private static final class SaleDue {
        private final UUID saleId;
        private final String saleNumber;
        private BigDecimal due;

        private SaleDue(UUID saleId, String saleNumber, BigDecimal due) {
            this.saleId = saleId;
            this.saleNumber = saleNumber;
            this.due = due;
        }

        private UUID saleId() { return saleId; }
        private String saleNumber() { return saleNumber; }
        private BigDecimal due() { return due; }
        private void subtractDue(BigDecimal applied) { this.due = this.due.subtract(applied).max(ZERO).setScale(4); }
    }
}
