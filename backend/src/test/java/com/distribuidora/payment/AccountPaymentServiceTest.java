package com.distribuidora.payment;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.payment.application.AccountPaymentService;
import com.distribuidora.payment.application.AccountPaymentService.PaymentCommand;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccountPaymentServiceTest {
    private record TestPayment(BigDecimal amount, String method, String transferReference, UUID saleId)
        implements PaymentCommand { }

    private JdbcTemplate jdbc;
    private AuditService audit;
    private CurrentUserAccess currentUser;
    private AccountPaymentService service;
    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        audit = mock(AuditService.class);
        currentUser = mock(CurrentUserAccess.class);
        when(currentUser.isAdmin()).thenReturn(true);
        when(currentUser.userId()).thenReturn(actorId);
        service = new AccountPaymentService(jdbc, audit, currentUser);
        authenticate("ADMIN_ALL", "ACCOUNT_SETTLE", "SALE_PAYMENT");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void settleDeniesMissingPermissionAndRejectsInvalidBatchInputs() {
        UUID customerId = UUID.randomUUID();
        authenticate("SALE_PAYMENT");
        assertThatThrownBy(() -> service.settle(customerId, List.of(new TestPayment(BigDecimal.TEN, "CASH", null, null))))
            .isInstanceOf(AccessDeniedException.class);

        authenticate("ADMIN_ALL");
        assertThatThrownBy(() -> service.settle(customerId, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.settle(customerId, List.of(
            new TestPayment(BigDecimal.TEN, "CASH", null, UUID.randomUUID()))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.settle(customerId, List.of(
            new TestPayment(BigDecimal.TEN, "BANK_TRANSFER", "  ", null))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.settle(customerId, List.of(
            new TestPayment(BigDecimal.TEN, "CASH", "REF-1", null))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.settle(customerId, List.of(
            new TestPayment(new BigDecimal("10.12345"), "CASH", null, null))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settleAllocatesAcrossOpenSalesInFifoOrderAndAuditsResult() {
        UUID customerId = UUID.randomUUID();
        UUID sale1 = UUID.randomUUID();
        UUID sale2 = UUID.randomUUID();
        List<Map<String, Object>> candidates = List.of(
            saleRow(sale1, "V-001", "100.0000", "20.0000", Instant.parse("2026-10-01T10:00:00Z")),
            saleRow(sale2, "V-002", "150.0000", "0.0000", Instant.parse("2026-10-02T10:00:00Z")));
        when(jdbc.queryForList(contains("from orders.orders o join sale.sales s"), eq(customerId))).thenReturn(candidates);
        when(jdbc.queryForMap("select id, balance from customer.customers where id = ? for update", customerId))
            .thenReturn(Map.of("id", customerId, "balance", new BigDecimal("230.0000")));
        when(jdbc.queryForObject(contains("from customer.account_ledger where customer_id = ?"),
            eq(BigDecimal.class), eq(customerId))).thenReturn(new BigDecimal("230.0000"));
        when(jdbc.queryForList(contains("from customer.account_ledger where sale_id in"), eq(sale1), eq(sale2)))
            .thenReturn(List.of(
                Map.of("sale_id", sale1, "balance", new BigDecimal("80.0000")),
                Map.of("sale_id", sale2, "balance", new BigDecimal("150.0000"))));

        var payments = List.of(
            new TestPayment(new BigDecimal("100.00"), "CASH", null, null),
            new TestPayment(new BigDecimal("130.00"), "BANK_TRANSFER", "  TRF-42 ", null));

        var result = service.settle(customerId, payments);

        assertThat(result.received()).isEqualByComparingTo("230.0000");
        assertThat(result.balanceBefore()).isEqualByComparingTo("230.0000");
        assertThat(result.balanceAfter()).isEqualByComparingTo("0.0000");
        assertThat(result.allocationMode()).isEqualTo("FIFO");
        assertThat(result.allocations()).hasSize(3);
        assertThat(result.allocations().get(0).saleId()).isEqualTo(sale1);
        assertThat(result.allocations().get(0).amount()).isEqualByComparingTo("80.0000");
        assertThat(result.allocations().get(1).saleId()).isEqualTo(sale2);
        assertThat(result.allocations().get(1).amount()).isEqualByComparingTo("20.0000");
        assertThat(result.allocations().get(2).saleId()).isEqualTo(sale2);
        assertThat(result.allocations().get(2).amount()).isEqualByComparingTo("130.0000");
        verify(jdbc).update("update customer.customers set balance = balance - ? where id = ?",
            new BigDecimal("230.0000"), customerId);
        verify(audit, org.mockito.Mockito.times(2)).recordWithinTransaction(eq(actorId), eq("ACCOUNT_PAYMENT_APPLY"), eq("CUSTOMER"),
            eq(customerId.toString()), eq("SUCCESS"), anyMap());
    }

    @Test
    void settleAllowsPartialPaymentWhenEnabledAndRejectsMismatchWhenFullSettlementRequired() {
        UUID customerId = UUID.randomUUID();
        UUID sale1 = UUID.randomUUID();
        when(jdbc.queryForList(contains("from orders.orders o join sale.sales s"), eq(customerId)))
            .thenReturn(List.of(saleRow(sale1, "V-001", "100.0000", "0.0000", Instant.parse("2026-10-01T10:00:00Z"))));
        when(jdbc.queryForMap("select id, balance from customer.customers where id = ? for update", customerId))
            .thenReturn(Map.of("id", customerId, "balance", new BigDecimal("100.0000")));
        when(jdbc.queryForObject(contains("from customer.account_ledger where customer_id = ?"),
            eq(BigDecimal.class), eq(customerId))).thenReturn(new BigDecimal("100.0000"));
        when(jdbc.queryForList(contains("from customer.account_ledger where sale_id in"), eq(sale1)))
            .thenReturn(List.of(Map.of("sale_id", sale1, "balance", new BigDecimal("100.0000"))));

        var partial = List.of(new TestPayment(new BigDecimal("40.00"), "CASH", null, null));
        assertThatThrownBy(() -> service.settle(customerId, partial, false))
            .isInstanceOf(IllegalStateException.class);

        var result = service.settle(customerId, partial, true);
        assertThat(result.balanceBefore()).isEqualByComparingTo("100.0000");
        assertThat(result.balanceAfter()).isEqualByComparingTo("60.0000");
        assertThatThrownBy(() -> service.settle(customerId, List.of(new TestPayment(new BigDecimal("120.00"), "CASH", null, null)), true))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void applyTargetsSpecificSaleAndRejectsMissingOrCancelledSales() {
        UUID customerId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(jdbc.queryForList(contains("where s.id = ? and s.account_customer_id = ? for update"), eq(saleId), eq(customerId)))
            .thenReturn(List.of());

        assertThatThrownBy(() -> service.apply(customerId, new TestPayment(new BigDecimal("50"), "CASH", null, saleId)))
            .isInstanceOf(EmptyResultDataAccessException.class);

        when(jdbc.queryForList(contains("where s.id = ? and s.account_customer_id = ? for update"), eq(saleId), eq(customerId)))
            .thenReturn(List.of(Map.of("id", saleId, "sale_number", "V-010", "status", "CANCELLED",
                "total", new BigDecimal("50.0000"), "paid", BigDecimal.ZERO)));
        when(jdbc.queryForObject("select order_id from sale.sales where id = ?", UUID.class, saleId)).thenReturn(orderId);
        assertThatThrownBy(() -> service.apply(customerId, new TestPayment(new BigDecimal("50"), "CASH", null, saleId)))
            .isInstanceOf(IllegalStateException.class);

        when(jdbc.queryForList(contains("where s.id = ? and s.account_customer_id = ? for update"), eq(saleId), eq(customerId)))
            .thenReturn(List.of(Map.of("id", saleId, "sale_number", "V-010", "status", "DELIVERED",
                "total", new BigDecimal("80.0000"), "paid", BigDecimal.ZERO)));
        when(jdbc.queryForMap("select id, balance from customer.customers where id = ? for update", customerId))
            .thenReturn(Map.of("id", customerId, "balance", new BigDecimal("80.0000")));
        when(jdbc.queryForObject(contains("from customer.account_ledger where customer_id = ?"),
            eq(BigDecimal.class), eq(customerId))).thenReturn(new BigDecimal("80.0000"));
        when(jdbc.queryForList(contains("from customer.account_ledger where sale_id in"), eq(saleId)))
            .thenReturn(List.of(Map.of("sale_id", saleId, "balance", new BigDecimal("80.0000"))));

        var result = service.apply(customerId, new TestPayment(new BigDecimal("50.00"), "CASH", null, saleId));
        assertThat(result.allocationMode()).isEqualTo("SPECIFIC");
        assertThat(result.balanceAfter()).isEqualByComparingTo("30.0000");
    }

    @Test
    void applyPreviousDebtPaymentsReturnsZeroWhenEmptyAndRejectsOverpayment() {
        UUID accountId = UUID.randomUUID();
        assertThat(service.applyPreviousDebtPaymentsWithLockedContext(
            accountId, List.of(), List.of(), Map.of("balance", BigDecimal.TEN)))
            .isEqualByComparingTo("0.0000");
        verifyNoInteractions(jdbc);

        when(jdbc.queryForObject(contains("from customer.account_ledger where customer_id = ?"),
            eq(BigDecimal.class), eq(accountId))).thenReturn(new BigDecimal("25.0000"));
        assertThatThrownBy(() -> service.applyPreviousDebtPaymentsWithLockedContext(
            accountId,
            List.of(new TestPayment(new BigDecimal("30.00"), "CASH", null, null)),
            List.of(),
            Map.of("id", accountId, "balance", new BigDecimal("25.0000"))))
            .isInstanceOf(IllegalStateException.class);
    }

    private void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken(actorId.toString(), "fixture", authorities));
    }

    private static Map<String, Object> saleRow(UUID id, String number, String total, String paid, Instant createdAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("sale_number", number);
        row.put("status", "DELIVERED");
        row.put("total", new BigDecimal(total));
        row.put("paid", new BigDecimal(paid));
        row.put("created_at", Timestamp.from(createdAt));
        return row;
    }
}
