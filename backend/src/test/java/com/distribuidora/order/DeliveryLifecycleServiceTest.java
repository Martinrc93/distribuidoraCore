package com.distribuidora.order;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.order.api.DeliveryLifecycleDtos;
import com.distribuidora.order.application.DeliveryLifecycleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryLifecycleServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final InventoryMovementService inventory = mock(InventoryMovementService.class);
    private final AuditService audit = mock(AuditService.class);
    private final DeliveryLifecycleService service = new DeliveryLifecycleService(jdbc, inventory, audit);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsFailedAttemptWithoutChangingOrderState() {
        UUID orderId = UUID.randomUUID();
        UUID actorId = authenticate("SELLER");
        when(jdbc.queryForMap(contains("join sale.sales"), any(Object[].class))).thenReturn(confirmed(orderId));
        when(jdbc.queryForObject(contains("max(attempt_number)"), eq(Integer.class), any(Object[].class))).thenReturn(0);

        service.recordAttempt(orderId, new DeliveryLifecycleDtos.DeliveryAttemptRequest("FAILED", "No había nadie"));

        verify(jdbc).update(contains("insert into orders.delivery_attempts"), any(Object[].class));
        verify(jdbc, never()).update(contains("set status = 'DELIVERED'"), any(Object[].class));
        verify(audit).recordWithinTransaction(eq(actorId), eq("DELIVERY_ATTEMPT"), eq("ORDER"),
            eq(orderId.toString()), eq("SUCCESS"), anyMap());
    }

    @Test
    void deliveredAttemptUpdatesOrderAndSaleTogether() {
        UUID orderId = UUID.randomUUID();
        authenticate("SELLER");
        when(jdbc.queryForMap(contains("join sale.sales"), any(Object[].class))).thenReturn(confirmed(orderId));
        when(jdbc.queryForMap(contains("from customer.customers"), any(Object[].class))).thenReturn(Map.of("balance", BigDecimal.ZERO));
        when(jdbc.queryForObject(contains("from customer.account_ledger"), eq(BigDecimal.class), any(Object[].class)))
            .thenReturn(BigDecimal.ZERO.setScale(4));
        when(jdbc.queryForObject(contains("max(attempt_number)"), eq(Integer.class), any(Object[].class))).thenReturn(2);

        service.recordAttempt(orderId, new DeliveryLifecycleDtos.DeliveryAttemptRequest("DELIVERED", null));

        verify(jdbc).update(contains("insert into orders.delivery_attempts"), any(Object[].class));
        verify(jdbc).update(contains("update orders.orders set status = 'DELIVERED'"), any(Object[].class));
        verify(jdbc).update(contains("update sale.sales set status = 'DELIVERED'"), any(Object[].class));
    }

    @Test
    void rejectsCollectionOnFailedDeliveryBeforeDatabaseAccess() {
        var request = new DeliveryLifecycleDtos.DeliveryAttemptRequest("FAILED", "Closed", List.of(
            new DeliveryLifecycleDtos.DeliveryPaymentRequest("CASH", new BigDecimal("1.00"))), null);

        assertThatThrownBy(() -> service.recordAttempt(UUID.randomUUID(), request))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(jdbc, inventory, audit);
    }

    @Test
    void transferReferenceRequiresBankTransferPayment() {
        var request = new DeliveryLifecycleDtos.DeliveryAttemptRequest("DELIVERED", null, List.of(
            new DeliveryLifecycleDtos.DeliveryPaymentRequest("CASH", new BigDecimal("1.00"))), "TR-123");

        assertThatThrownBy(() -> service.recordAttempt(UUID.randomUUID(), request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("BANK_TRANSFER");

        verifyNoInteractions(jdbc, inventory, audit);
    }

    @Test
    void rejectsFailedAttemptWithoutNonBlankObservation() {
        assertThatThrownBy(() -> service.recordAttempt(UUID.randomUUID(),
            new DeliveryLifecycleDtos.DeliveryAttemptRequest("FAILED", "  ")))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc, inventory, audit);
    }

    @Test
    void rejectsCancellationOfPaidSale() {
        UUID orderId = UUID.randomUUID();
        authenticate("ADMIN_ALL");
        when(jdbc.queryForMap(contains("join sale.sales"), any(Object[].class)))
            .thenReturn(Map.of("order_id", orderId, "sale_id", UUID.randomUUID(), "customer_id", UUID.randomUUID(),
                "order_status", "CONFIRMED", "sale_status", "CONFIRMED", "paid", new BigDecimal("1.0000"),
                "total", new BigDecimal("10.0000")));

        assertThatThrownBy(() -> service.cancel(orderId))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(inventory, audit);
    }

    @Test
    void cancellationReversesEachPersistedSaleMovementAndCreditsOutstandingBalance() {
        UUID orderId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID firstProductId = UUID.randomUUID();
        UUID secondProductId = UUID.randomUUID();
        authenticate("ADMIN_ALL");
        when(jdbc.queryForMap(contains("join sale.sales"), any(Object[].class))).thenReturn(Map.of(
            "order_id", orderId, "sale_id", saleId, "customer_id", customerId,
            "order_status", "CONFIRMED", "sale_status", "CONFIRMED", "paid", BigDecimal.ZERO,
            "total", new BigDecimal("15.0000")));
        when(jdbc.queryForMap(contains("from customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("balance", new BigDecimal("20.0000")));
        when(jdbc.queryForList(contains("sum(quantity) as net_quantity"), any(Object[].class))).thenReturn(List.of(
            Map.of("depot_id", InventoryMovementService.DEFAULT_DEPOT_ID, "product_id", firstProductId, "net_quantity", new BigDecimal("-2.0")),
            Map.of("depot_id", InventoryMovementService.DEFAULT_DEPOT_ID, "product_id", secondProductId, "net_quantity", new BigDecimal("-1.5"))));

        service.cancel(orderId);

        verify(inventory).apply(InventoryMovementService.DEFAULT_DEPOT_ID, firstProductId, new BigDecimal("2.0"), "SALE_CANCELLATION",
            orderId, "Sale cancellation");
        verify(inventory).apply(InventoryMovementService.DEFAULT_DEPOT_ID, secondProductId, new BigDecimal("1.5"), "SALE_CANCELLATION",
            orderId, "Sale cancellation");
        verify(jdbc).update(contains("insert into customer.account_ledger"), any(Object[].class));
        verify(jdbc).update(contains("update customer.customers set balance = balance -"), any(Object[].class));
        verify(jdbc).update(contains("update orders.orders set status = 'CANCELLED'"), any(Object[].class));
        verify(jdbc).update(contains("update sale.sales set status = 'CANCELLED'"), any(Object[].class));
        verify(audit).recordWithinTransaction(any(), eq("ORDER_CANCEL"), eq("ORDER"), eq(orderId.toString()),
            eq("SUCCESS"), anyMap());
    }

    @Test
    void cancellationReadsNetSaleMovementsUsingBothOrderAndSaleReferences() {
        UUID orderId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        authenticate("ADMIN_ALL");
        when(jdbc.queryForMap(contains("join sale.sales"), any(Object[].class))).thenReturn(Map.of(
            "order_id", orderId, "sale_id", saleId, "customer_id", customerId,
            "order_status", "CONFIRMED", "sale_status", "CONFIRMED", "paid", BigDecimal.ZERO,
            "total", new BigDecimal("2.0000")));
        when(jdbc.queryForMap(contains("from customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("balance", BigDecimal.ZERO));
        when(jdbc.queryForList(contains("sum(quantity) as net_quantity"), eq(new Object[]{orderId, saleId})))
            .thenReturn(List.of(Map.of("depot_id", InventoryMovementService.DEFAULT_DEPOT_ID,
                "product_id", productId, "net_quantity", new BigDecimal("-2.0"))));

        service.cancel(orderId);

        verify(jdbc).queryForList(contains("reference_id in (?, ?)"), eq(new Object[]{orderId, saleId}));
    }

    @Test
    void cancellationDoesNotReverseAnAlreadyReversedMovement() {
        UUID orderId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        authenticate("ADMIN_ALL");
        when(jdbc.queryForMap(contains("join sale.sales"), any(Object[].class))).thenReturn(Map.of(
            "order_id", orderId, "sale_id", saleId, "customer_id", customerId,
            "order_status", "CONFIRMED", "sale_status", "CONFIRMED", "paid", BigDecimal.ZERO,
            "total", new BigDecimal("2.0000")));
        when(jdbc.queryForMap(contains("from customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("balance", BigDecimal.ZERO));
        when(jdbc.queryForList(contains("sum(quantity) as net_quantity"), any(Object[].class))).thenReturn(List.of());

        service.cancel(orderId);

        verifyNoInteractions(inventory);
    }

    @Test
    void cancellationSkipsCreditAndBalanceUpdateForZeroTotalSale() {
        UUID orderId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        authenticate("ADMIN_ALL");
        when(jdbc.queryForMap(contains("join sale.sales"), any(Object[].class))).thenReturn(Map.of(
            "order_id", orderId, "sale_id", saleId, "customer_id", customerId,
            "order_status", "CONFIRMED", "sale_status", "CONFIRMED", "paid", BigDecimal.ZERO,
            "total", BigDecimal.ZERO));
        when(jdbc.queryForMap(contains("from customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("balance", BigDecimal.ZERO));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.cancel(orderId);

        verify(jdbc, never()).update(contains("insert into customer.account_ledger"), any(Object[].class));
        verify(jdbc, never()).update(contains("update customer.customers set balance"), any(Object[].class));
    }

    @Test
    void cancellationCreditsPartialAccountDebt() {
        UUID orderId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        authenticate("ADMIN_ALL");
        when(jdbc.queryForMap(contains("join sale.sales"), any(Object[].class))).thenReturn(Map.of(
            "order_id", orderId, "sale_id", saleId, "customer_id", customerId,
            "order_status", "CONFIRMED", "sale_status", "CONFIRMED", "paid", BigDecimal.ZERO,
            "total", new BigDecimal("10.0000")));
        when(jdbc.queryForMap(contains("from customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("balance", new BigDecimal("10.0000")));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        service.cancel(orderId);

        verify(jdbc).update(contains("insert into customer.account_ledger"), any(Object[].class));
        verify(jdbc).update(contains("update customer.customers set balance = balance -"), any(Object[].class));
    }

    @Test
    void cancellationRejectsTerminalOrderBeforeInventoryOrLedgerChanges() {
        UUID orderId = UUID.randomUUID();
        authenticate("ADMIN_ALL");
        when(jdbc.queryForMap(contains("join sale.sales"), any(Object[].class))).thenReturn(Map.of(
            "order_id", orderId, "sale_id", UUID.randomUUID(), "customer_id", UUID.randomUUID(),
            "order_status", "DELIVERED", "sale_status", "DELIVERED", "paid", BigDecimal.ZERO,
            "total", new BigDecimal("10.0000")));

        assertThatThrownBy(() -> service.cancel(orderId))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(inventory, audit);
        verify(jdbc, never()).update(contains("insert into customer.account_ledger"), any(Object[].class));
    }

    private Map<String, Object> confirmed(UUID orderId) {
        return Map.of("order_id", orderId, "sale_id", UUID.randomUUID(), "customer_id", UUID.randomUUID(),
            "order_status", "CONFIRMED", "sale_status", "CONFIRMED", "paid", BigDecimal.ZERO,
            "total", new BigDecimal("10.0000"));
    }

    private UUID authenticate(String... authorities) {
        UUID id = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken(id.toString(), "n/a", authorities));
        return id;
    }
}
