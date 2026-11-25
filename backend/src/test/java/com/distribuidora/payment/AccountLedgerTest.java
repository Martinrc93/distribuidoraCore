package com.distribuidora.payment;

import com.distribuidora.payment.application.AccountLedger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountLedgerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AccountLedger ledger = new AccountLedger(jdbc);

    @Test
    void lockAccountUsesForUpdateRowLock() {
        UUID accountId = UUID.randomUUID();
        Map<String, Object> row = Map.of("id", accountId, "balance", new BigDecimal("250.0000"));
        when(jdbc.queryForMap("select id, balance from customer.customers where id = ? for update", accountId)).thenReturn(row);

        assertThat(ledger.lockAccount(accountId)).isEqualTo(row);
    }

    @Test
    void allowsAdminAlwaysAndDeniesSellerOnResellerAccounts() {
        UUID resellerId = UUID.randomUUID();
        UUID regularId = UUID.randomUUID();
        ledger.requireCollectionAllowed(resellerId, true);
        verifyNoInteractions(jdbc);

        when(jdbc.queryForObject("select kind from customer.customers where id = ?", String.class, regularId))
            .thenReturn("REGULAR");
        ledger.requireCollectionAllowed(regularId, false);

        when(jdbc.queryForObject("select kind from customer.customers where id = ?", String.class, resellerId))
            .thenReturn("RESELLER");
        assertThatThrownBy(() -> ledger.requireCollectionAllowed(resellerId, false))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void recordsDebitsCreditsBalanceUpdatesAndPayments() {
        UUID accountId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.parse("2026-10-01T12:00:00Z"));
        BigDecimal amount = new BigDecimal("125.5000");

        when(jdbc.queryForObject(
            "select coalesce(sum(case when entry_type = 'DEBIT' then amount else -amount end), 0) from customer.account_ledger where sale_id = ?",
            BigDecimal.class, saleId)).thenReturn(amount);

        assertThat(ledger.saleBalance(saleId)).isEqualByComparingTo("125.5000");

        ledger.debit(accountId, saleId, amount, at);
        verify(jdbc).update(
            eq("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'DEBIT', ?, ?)"),
            any(UUID.class), eq(accountId), eq(saleId), eq(amount), eq(at));

        ledger.credit(accountId, saleId, amount, at);
        verify(jdbc).update(
            eq("insert into customer.account_ledger(id, customer_id, sale_id, entry_type, amount, created_at) values (?, ?, ?, 'CREDIT', ?, ?)"),
            any(UUID.class), eq(accountId), eq(saleId), eq(amount), eq(at));

        ledger.increaseBalance(accountId, amount);
        verify(jdbc).update("update customer.customers set balance = balance + ? where id = ?", amount, accountId);

        ledger.decreaseBalance(accountId, amount);
        verify(jdbc).update("update customer.customers set balance = balance - ? where id = ?", amount, accountId);

        UUID paymentId = ledger.recordPayment(accountId, saleId, amount, "BANK_TRANSFER", "OP-99", at);
        assertThat(paymentId).isNotNull();
        verify(jdbc).update(
            "insert into payment.payments(id, sale_id, customer_id, amount, method, transfer_reference, created_at) values (?, ?, ?, ?, ?, ?, ?)",
            paymentId, saleId, accountId, amount, "BANK_TRANSFER", "OP-99", at);
    }
}
