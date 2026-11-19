package com.distribuidora.payment.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;

/**
 * Single write path of customer account entries, balances and payments. Every entry and payment
 * belongs to the account that owes the sale ({@code sale.sales.account_customer_id}); callers read it
 * from the sale they locked, never from a request. Composite foreign keys reject any other account.
 * Lock order: the affected sales first, by creation time and id, then the account row.
 */
public final class AccountLedger {
    private static final String DEBIT = "insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'DEBIT', ?, ?)";
    private static final String CREDIT = "insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'CREDIT', ?, ?)";
    private static final String PAYMENT = "insert into payment.payments(id, sale_id, customer_id, amount, method, transfer_reference, created_at) values (?, ?, ?, ?, ?, ?, ?)";
    private static final String SALE_BALANCE = "select coalesce(sum(case when entry_type = 'DEBIT' then amount else -amount end), 0) "
        + "from customer.account_ledger where sale_id = ?";

    private final JdbcTemplate jdbc;

    public AccountLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the account row that owes the debt and returns its id and persisted balance. */
    public Map<String, Object> lockAccount(UUID accountId) {
        return jdbc.queryForMap("select id, balance from customer.customers where id = ? for update", accountId);
    }

    /** Collections on a reseller account are registered by an administrator only. */
    public void requireCollectionAllowed(UUID accountId, boolean administrator) {
        if (administrator) return;
        String kind = jdbc.queryForObject("select kind from customer.customers where id = ?", String.class, accountId);
        if ("RESELLER".equals(kind)) {
            throw new AccessDeniedException("Los cobros de cuentas de revendedor los registra un administrador");
        }
    }

    /** Net ledger balance of one sale: debits minus credits. */
    public BigDecimal saleBalance(UUID saleId) {
        return jdbc.queryForObject(SALE_BALANCE, BigDecimal.class, saleId);
    }

    public void debit(UUID accountId, UUID saleId, BigDecimal amount, Timestamp at) {
        jdbc.update(DEBIT, UUID.randomUUID(), accountId, saleId, amount, at);
    }

    public void credit(UUID accountId, UUID saleId, BigDecimal amount, Timestamp at) {
        jdbc.update(CREDIT, UUID.randomUUID(), accountId, saleId, amount, at);
    }

    /** Adds to the persisted balance of the account, in the same transaction as its ledger entries. */
    public void increaseBalance(UUID accountId, BigDecimal amount) {
        jdbc.update("update customer.customers set balance = balance + ? where id = ?", amount, accountId);
    }

    /** Subtracts from the persisted balance of the account, in the same transaction as its ledger entries. */
    public void decreaseBalance(UUID accountId, BigDecimal amount) {
        jdbc.update("update customer.customers set balance = balance - ? where id = ?", amount, accountId);
    }

    /** Registers money received for a sale; the payment belongs to the account that owes it. */
    public UUID recordPayment(UUID accountId, UUID saleId, BigDecimal amount, String method,
                              String transferReference, Timestamp at) {
        UUID paymentId = UUID.randomUUID();
        jdbc.update(PAYMENT, paymentId, saleId, accountId, amount, method, transferReference, at);
        return paymentId;
    }
}
