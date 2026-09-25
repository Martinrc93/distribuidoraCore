package com.distribuidora.order;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.order.api.OrderConfirmationDtos;
import com.distribuidora.order.application.OrderCalculationService;
import com.distribuidora.order.application.OrderConfirmationService;
import com.distribuidora.pricing.application.PricingQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderConfirmationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PricingQueryService pricing = mock(PricingQueryService.class);
    private final OrderCalculationService calculation = new OrderCalculationService();
    private final InventoryMovementService inventory = mock(InventoryMovementService.class);
    private final AuditService audit = mock(AuditService.class);
    private final OrderConfirmationService service = new OrderConfirmationService(jdbc, pricing, calculation, inventory, audit);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void confirmsCashOrderAndPersistsSnapshots() {
        authenticate("ORDER_CREATE");
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE"));
        when(pricing.resolve(customerId, productId, null)).thenReturn(Map.of(
            "priceListId", listId, "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10.0000")));

        var response = service.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "key-1", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, new BigDecimal("2.0"), BigDecimal.ZERO, null)),
            BigDecimal.ZERO,
            List.of(new OrderConfirmationDtos.PaymentRequest("CASH", new BigDecimal("20.00")))));

        assertThat(response.orderId()).isNotNull();
        assertThat(response.saleId()).isNotNull();
        assertThat(response.total()).isEqualByComparingTo("20.0000");
        assertThat(response.paid()).isEqualByComparingTo("20.0000");
        verify(inventory).apply(eq(productId), argThat(value -> value.compareTo(new BigDecimal("-2.0")) == 0),
            eq("SALE"), eq(response.orderId()), eq("Order confirmation"));
        verify(jdbc).update(contains("payment.payments"), any(Object[].class));
        verify(jdbc, never()).update(contains("account_ledger"), any(Object[].class));
        verify(audit).recordWithinTransaction(any(), eq("ORDER_CONFIRM"), eq("ORDER"), eq(response.orderId().toString()), eq("SUCCESS"), anyMap());
        verify(jdbc).queryForObject(contains("pg_advisory_xact_lock"), eq(Object.class), anyLong());
        var orderInsert = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("idempotency_fingerprint"), orderInsert.capture());
        assertThat((String) orderInsert.getValue()[8]).hasSize(64);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sameFingerprintReturnsCommittedResponseWithoutResolvingPricingAgain() throws Exception {
        authenticate("ORDER_CREATE");
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        AtomicInteger lookups = new AtomicInteger();
        AtomicReference<OrderConfirmationDtos.ConfirmationResponse> committed = new AtomicReference<>();
        AtomicReference<String> storedFingerprint = new AtomicReference<>();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            if (lookups.incrementAndGet() == 1) return List.of();
            var original = committed.get();
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getObject("order_id", UUID.class)).thenReturn(original.orderId());
            when(resultSet.getObject("sale_id", UUID.class)).thenReturn(original.saleId());
            when(resultSet.getObject("customer_id", UUID.class)).thenReturn(customerId);
            when(resultSet.getString("order_number")).thenReturn(original.orderNumber());
            when(resultSet.getString("sale_number")).thenReturn(original.saleNumber());
            when(resultSet.getBigDecimal("total")).thenReturn(original.total());
            when(resultSet.getBigDecimal("paid")).thenReturn(original.paid());
            when(resultSet.getString("idempotency_fingerprint")).thenReturn(storedFingerprint.get());
            return List.of(((RowMapper) invocation.getArgument(1)).mapRow(resultSet, 0));
        });
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE"));
        when(pricing.resolve(customerId, productId, null)).thenReturn(Map.of(
            "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10")));

        var request = new OrderConfirmationDtos.ConfirmationRequest(
            "retry-key", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of(new OrderConfirmationDtos.PaymentRequest("CASH", new BigDecimal("10"))));
        var first = service.confirm(request);
        var fingerprint = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("idempotency_fingerprint"), fingerprint.capture());
        storedFingerprint.set((String) fingerprint.getValue()[8]);
        committed.set(first);

        var second = service.confirm(request);

        assertThat(second).isEqualTo(first);
        verify(pricing, times(1)).resolve(customerId, productId, null);

        var changedRequest = new OrderConfirmationDtos.ConfirmationRequest(
            request.idempotencyKey(), customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, new BigDecimal("2"), BigDecimal.ZERO, null)),
            request.orderDiscountPercent(), request.payments());
        assertThatThrownBy(() -> service.confirm(changedRequest))
            .isInstanceOf(com.distribuidora.order.application.IdempotencyConflictException.class);
        verify(pricing, times(1)).resolve(customerId, productId, null);
    }

    @Test
    void rejectsPriceOverrideWithoutAdminAllBeforeSideEffects() {
        authenticate("ORDER_CREATE");
        var request = new OrderConfirmationDtos.ConfirmationRequest(
            "key-2", UUID.randomUUID(), null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), new BigDecimal("1.0"), BigDecimal.ZERO,
                new BigDecimal("9.00"))), BigDecimal.ZERO, List.of());

        assertThatThrownBy(() -> service.confirm(request))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(pricing, inventory, audit);
    }

    @Test
    void rejectsLineDiscountWithoutAdminAllBeforeSideEffects() {
        authenticate("ORDER_CREATE");
        var request = new OrderConfirmationDtos.ConfirmationRequest(
            "key-line-discount", UUID.randomUUID(), null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), new BigDecimal("1.0"),
                new BigDecimal("5.00"), null)), BigDecimal.ZERO, List.of());

        assertThatThrownBy(() -> service.confirm(request))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(pricing, inventory, audit);
    }

    @Test
    void rejectsOrderDiscountWithoutAdminAllBeforeSideEffects() {
        authenticate("ORDER_CREATE");
        var request = new OrderConfirmationDtos.ConfirmationRequest(
            "key-order-discount", UUID.randomUUID(), null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), new BigDecimal("1.0"),
                BigDecimal.ZERO, null)), new BigDecimal("5.00"), List.of());

        assertThatThrownBy(() -> service.confirm(request))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(pricing, inventory, audit);
    }

    @Test
    void allowsLineAndOrderDiscountsWithAdminAll() {
        authenticate("ORDER_CREATE", "ADMIN_ALL");
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE"));
        when(pricing.resolve(customerId, productId, null)).thenReturn(Map.of(
            "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10.0000")));

        var response = service.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "key-admin-discounts", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, new BigDecimal("5.00"), null)),
            new BigDecimal("5.00"), List.of()));

        assertThat(response.total()).isEqualByComparingTo("9.0250");
        verify(inventory).apply(eq(productId), any(), eq("SALE"), eq(response.orderId()), eq("Order confirmation"));
    }

    @Test
    void rejectsNullRequestBeforeAnySideEffect() {
        assertThatThrownBy(() -> service.confirm(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("request");
        verifyNoInteractions(jdbc, pricing, inventory, audit);
    }

    @Test
    void confirmedOrderEditRequiresAdminBeforeReadingOrChangingData() {
        authenticate("ORDER_CREATE");

        assertThatThrownBy(() -> service.editConfirmed(UUID.randomUUID(), new com.distribuidora.order.api.OrderEditDtos.EditRequest(
            null, List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO)))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verifyNoInteractions(jdbc, pricing, inventory, audit);
    }

    @Test
    void rejectsNullRequiredFieldsBeforeAnySideEffect() {
        var request = new OrderConfirmationDtos.ConfirmationRequest(
            "key-invalid", null, null, null, null, null);

        assertThatThrownBy(() -> service.confirm(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("obligatorios");
        verifyNoInteractions(jdbc, pricing, inventory, audit);
    }

    @Test
    void rejectsInvalidPaymentMethodDirectly() {
        var request = validRequest(List.of(new OrderConfirmationDtos.PaymentRequest("CHEQUE", BigDecimal.ONE)));

        assertThatThrownBy(() -> service.confirm(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("method");
        verifyNoInteractions(jdbc, pricing, inventory, audit);
    }

    @Test
    void rejectsValuesWithMoreThanFourDecimalsDirectly() {
        var request = new OrderConfirmationDtos.ConfirmationRequest(
            "key-scale", UUID.randomUUID(), null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), new BigDecimal("1.00000"),
                new BigDecimal("0.00000"), null)), new BigDecimal("0.00000"), List.of());

        assertThatThrownBy(() -> service.confirm(request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("decimales");
        verifyNoInteractions(jdbc, pricing, inventory, audit);
    }

    @Test
    void defaultsMissingPaymentsToCustomerAccount() {
        authenticate("ORDER_CREATE");
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(pricing.resolve(any(), any(), any())).thenReturn(Map.of(
            "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("5.0000")));
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE"));
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());
        service.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "key-3", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, null));

        verify(jdbc).update(contains("account_ledger"), any(Object[].class));
        verify(jdbc).update(contains("customer.customers"), any(Object[].class));
        verify(jdbc, never()).update(contains("payment.payments"), any(Object[].class));
    }

    @Test
    void cashOnlyPartialCreatesRemainderAsAccountDebt() {
        var response = confirmWithPayments(List.of(new OrderConfirmationDtos.PaymentRequest("CASH", new BigDecimal("5"))));

        assertThat(response.paid()).isEqualByComparingTo("5.0000");
        assertThat(response.balance()).isEqualByComparingTo("5.0000");
        var ledger = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("account_ledger"), ledger.capture());
        assertThat((BigDecimal) ledger.getValue()[3]).isEqualByComparingTo("5.0000");
        verify(jdbc).update(contains("customer.customers set balance"), any(Object[].class));
    }

    @Test
    void explicitCustomerAccountDoesNotReplaceRemainderDebt() {
        var response = confirmWithPayments(List.of(
            new OrderConfirmationDtos.PaymentRequest("CASH", new BigDecimal("5")),
            new OrderConfirmationDtos.PaymentRequest("CUSTOMER_ACCOUNT", new BigDecimal("5"))));

        assertThat(response.paid()).isEqualByComparingTo("5.0000");
        assertThat(response.balance()).isEqualByComparingTo("5.0000");
        var ledger = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("account_ledger"), ledger.capture());
        assertThat((BigDecimal) ledger.getValue()[3]).isEqualByComparingTo("5.0000");
        verify(jdbc, times(1)).update(contains("customer.customers set balance"), any(Object[].class));
    }

    @Test
    void combinedMonetaryPaymentsAndAccountRowUseMonetaryTotal() {
        var response = confirmWithPayments(List.of(
            new OrderConfirmationDtos.PaymentRequest("CASH", new BigDecimal("3")),
            new OrderConfirmationDtos.PaymentRequest("BANK_TRANSFER", new BigDecimal("2")),
            new OrderConfirmationDtos.PaymentRequest("CUSTOMER_ACCOUNT", new BigDecimal("1"))));

        assertThat(response.paid()).isEqualByComparingTo("5.0000");
        assertThat(response.balance()).isEqualByComparingTo("5.0000");
        verify(jdbc, times(2)).update(contains("payment.payments"), any(Object[].class));
        var ledger = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(contains("account_ledger"), ledger.capture());
        assertThat((BigDecimal) ledger.getAllValues().get(0)[3]).isEqualByComparingTo("1.0000");
        assertThat((BigDecimal) ledger.getAllValues().get(1)[3]).isEqualByComparingTo("4.0000");
    }

    @Test
    void rejectsAccountAmountThatMakesAllPaymentEntriesExceedTotal() {
        assertThatThrownBy(() -> confirmWithPayments(List.of(
            new OrderConfirmationDtos.PaymentRequest("CASH", new BigDecimal("5")),
            new OrderConfirmationDtos.PaymentRequest("CUSTOMER_ACCOUNT", new BigDecimal("10")))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("superar el total");
    }

    @Test
    void noPaymentsCreatesEntireTotalAsAccountDebt() {
        var response = confirmWithPayments(null);

        assertThat(response.paid()).isEqualByComparingTo("0.0000");
        assertThat(response.balance()).isEqualByComparingTo("10.0000");
        var ledger = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("account_ledger"), ledger.capture());
        assertThat((BigDecimal) ledger.getValue()[3]).isEqualByComparingTo("10.0000");
    }

    private OrderConfirmationDtos.ConfirmationResponse confirmWithPayments(
        List<OrderConfirmationDtos.PaymentRequest> payments) {
        authenticate("ORDER_CREATE");
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE"));
        when(pricing.resolve(customerId, productId, null)).thenReturn(Map.of(
            "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10.0000")));
        return service.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            UUID.randomUUID().toString(), customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, payments));
    }

    private void authenticate(String... authorities) {
        var authentication = new TestingAuthenticationToken(UUID.randomUUID().toString(), "n/a", authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private OrderConfirmationDtos.ConfirmationRequest validRequest(List<OrderConfirmationDtos.PaymentRequest> payments) {
        return new OrderConfirmationDtos.ConfirmationRequest(
            "key-valid", UUID.randomUUID(), null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, payments);
    }
}
