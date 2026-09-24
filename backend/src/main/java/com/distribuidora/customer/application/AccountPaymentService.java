package com.distribuidora.customer.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.customer.api.AccountPaymentDtos;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AccountPaymentService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4);

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;

    public AccountPaymentService(JdbcTemplate jdbc, AuditService audit, CurrentUserAccess currentUser) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional
    public AccountPaymentDtos.PaymentResponse apply(UUID customerId, AccountPaymentDtos.PaymentRequest request) {
        validate(customerId, request);
        currentUser.requireCustomerAccess(customerId);

        // Lock sale rows before the customer so delivery, editing and payment allocation use one lock order.
        List<Map<String, Object>> candidates = lockCandidateSales(customerId, request.saleId());
        Map<String, Object> customer = jdbc.queryForMap(
            "select id, balance from customer.customers where id = ? for update", customerId);
        BigDecimal balanceBefore = decimal(customer.get("balance")).setScale(4);
        BigDecimal ledgerBalance = jdbc.queryForObject(
            "select coalesce(sum(case when entry_type = 'DEBIT' then amount else -amount end), 0) "
                + "from customer.account_ledger where customer_id = ?", BigDecimal.class, customerId).setScale(4);
        BigDecimal globallyAvailable = balanceBefore.max(ZERO).min(ledgerBalance.max(ZERO));
        BigDecimal amount = request.amount().setScale(4);
        if (amount.compareTo(globallyAvailable) > 0) {
            throw new IllegalStateException("El cobro supera la deuda vigente del cliente");
        }

        List<SaleDue> dues = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            UUID saleId = uuid(candidate.get("id"));
            BigDecimal ledgerDue = jdbc.queryForObject(
                "select coalesce(sum(case when entry_type = 'DEBIT' then amount else -amount end), 0) "
                    + "from customer.account_ledger where sale_id = ?", BigDecimal.class, saleId).setScale(4);
            BigDecimal unpaidBySale = decimal(candidate.get("total"))
                .subtract(decimal(candidate.get("paid"))).max(ZERO).setScale(4);
            BigDecimal due = ledgerDue.max(ZERO).min(unpaidBySale);
            if (due.signum() > 0) {
                dues.add(new SaleDue(saleId, String.valueOf(candidate.get("sale_number")),
                    decimal(candidate.get("paid")).setScale(4), due));
            }
        }

        List<AccountPaymentDtos.Allocation> allocations = new ArrayList<>();
        BigDecimal remaining = amount;
        Timestamp now = Timestamp.from(Instant.now());
        String transferReference = normalize(request.transferReference());
        for (SaleDue saleDue : dues) {
            if (remaining.signum() == 0) break;
            BigDecimal applied = remaining.min(saleDue.due()).setScale(4);
            UUID paymentId = UUID.randomUUID();
            jdbc.update("insert into payment.payments(id, sale_id, customer_id, amount, method, transfer_reference, created_at) values (?, ?, ?, ?, ?, ?, ?)",
                paymentId, saleDue.saleId(), customerId, applied, request.method(),
                "BANK_TRANSFER".equals(request.method()) ? transferReference : null, now);
            jdbc.update("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'CREDIT', ?, ?)",
                UUID.randomUUID(), customerId, saleDue.saleId(), applied, now);
            jdbc.update("update sale.sales set paid = paid + ? where id = ?", applied, saleDue.saleId());
            allocations.add(new AccountPaymentDtos.Allocation(saleDue.saleId(), saleDue.saleNumber(), paymentId, applied));
            remaining = remaining.subtract(applied).setScale(4);
        }
        if (remaining.signum() != 0) {
            throw new IllegalStateException("No hay deuda abierta suficiente para imputar el cobro");
        }

        BigDecimal balanceAfter = balanceBefore.subtract(amount).setScale(4);
        jdbc.update("update customer.customers set balance = balance - ? where id = ?", amount, customerId);
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
        audit.recordWithinTransaction(actorId(), "ACCOUNT_PAYMENT_APPLY", "CUSTOMER", customerId.toString(), "SUCCESS", details);

        return new AccountPaymentDtos.PaymentResponse(customerId, amount, balanceBefore,
            balanceAfter, allocationMode, allocations);
    }

    private List<Map<String, Object>> lockCandidateSales(UUID customerId, UUID saleId) {
        if (saleId != null) {
            List<Map<String, Object>> rows = jdbc.queryForList("select id, sale_number, status, total, paid "
                + "from sale.sales where id = ? and customer_id = ? for update", saleId, customerId);
            if (rows.isEmpty()) throw new EmptyResultDataAccessException(1);
            if (!"CONFIRMED".equals(rows.get(0).get("status")) && !"DELIVERED".equals(rows.get(0).get("status"))) {
                throw new IllegalStateException("Solo se pueden cobrar ventas confirmadas o entregadas");
            }
            return rows;
        }
        return jdbc.queryForList("select s.id, s.sale_number, s.status, s.total, s.paid "
            + "from sale.sales s where s.customer_id = ? and s.status in ('CONFIRMED', 'DELIVERED') "
            + "and exists (select 1 from customer.account_ledger l where l.sale_id = s.id) "
            + "order by s.created_at, s.id for update of s", customerId);
    }

    private void validate(UUID customerId, AccountPaymentDtos.PaymentRequest request) {
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

    private UUID actorId() {
        try {
            return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo identificar al usuario actual");
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

    private record SaleDue(UUID saleId, String saleNumber, BigDecimal paid, BigDecimal due) { }
}
