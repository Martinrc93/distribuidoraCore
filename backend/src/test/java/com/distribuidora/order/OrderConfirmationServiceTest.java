package com.distribuidora.order;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.order.api.OrderConfirmationDtos;
import com.distribuidora.order.application.OrderCalculationService;
import com.distribuidora.order.application.OrderConfirmationService;
import com.distribuidora.pricing.application.PricingQueryService;
import com.distribuidora.pricing.application.CommercialDiscountRuleQueryService;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import com.distribuidora.order.api.OrderEditDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final CommercialDiscountRuleQueryService discountRules = mock(CommercialDiscountRuleQueryService.class);
    private final OrderConfirmationService serviceWithDiscountRules = new OrderConfirmationService(
        jdbc, pricing, discountRules, calculation, inventory, audit);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"2026-09-29", "2027-01-10"})
    void storesChosenOrderAndSaleDateWhileKeepingLedgerExecutionTime(String chosenDate) {
        authenticate("ORDER_CREATE");
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE"));
        when(pricing.resolve(customerId, productId, null)).thenReturn(Map.of(
            "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10")));
        Instant before = Instant.now();
        service.confirm(new OrderConfirmationDtos.ConfirmationRequest("dated-order", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of(), null, BigDecimal.ZERO, chosenDate == null ? null : LocalDate.parse(chosenDate)));
        Instant after = Instant.now();
        var orderInsert = org.mockito.ArgumentCaptor.forClass(Object[].class);
        var saleInsert = org.mockito.ArgumentCaptor.forClass(Object[].class);
        var ledgerInsert = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into orders.orders"), orderInsert.capture());
        verify(jdbc).update(contains("insert into sale.sales"), saleInsert.capture());
        verify(jdbc).update(contains("insert into customer.account_ledger"), ledgerInsert.capture());
        Timestamp registeredAt = (Timestamp) orderInsert.getValue()[7];
        Timestamp ledgerAt = (Timestamp) ledgerInsert.getValue()[4];
        assertThat(saleInsert.getValue()[6]).isEqualTo(registeredAt);
        assertThat(ledgerAt.toInstant()).isBetween(before, after);
        if (chosenDate == null) assertThat(registeredAt).isEqualTo(ledgerAt);
        else assertThat(registeredAt.toInstant().atZone(ZoneId.of("America/Argentina/Buenos_Aires")).toLocalDate())
            .isEqualTo(LocalDate.parse(chosenDate));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10000})
    void rejectsDatesOutsideSupportedYearsBeforeSideEffects(int year) {
        authenticate("ORDER_CREATE");
        var request = new OrderConfirmationDtos.ConfirmationRequest("invalid-date", UUID.randomUUID(), null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of(), null, BigDecimal.ZERO, LocalDate.of(year, 1, 1));
        assertThatThrownBy(() -> service.confirm(request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("orderDate");
        verifyNoInteractions(jdbc, pricing, inventory, audit);
    }

    @Test
    void storesPreviousBalanceSeparatelyWithoutDuplicatingDebtOrInventory() {
        authenticate("ORDER_CREATE");
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE", "balance", new BigDecimal("100")));
        when(jdbc.queryForObject(eq("select balance from customer.customers where id = ? for update"), eq(BigDecimal.class), eq(customerId)))
            .thenReturn(new BigDecimal("100"));
        when(pricing.resolve(customerId, productId, null)).thenReturn(Map.of("priceListId", listId, "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10")));
        var response = service.confirm(new OrderConfirmationDtos.ConfirmationRequest("previous-balance", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, new BigDecimal("2"), BigDecimal.ZERO, null)), BigDecimal.ZERO, List.of(), null, new BigDecimal("40")));
        assertThat(response.total()).isEqualByComparingTo("20");
        assertThat(response.balance()).isEqualByComparingTo("20");
        assertThat(response.previousBalanceAmount()).isEqualByComparingTo("40");
        var insert = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("previous_balance_amount"), insert.capture());
        assertThat((BigDecimal) insert.getValue()[insert.getValue().length - 1]).isEqualByComparingTo("40");
        var debit = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into customer.account_ledger"), debit.capture());
        assertThat((BigDecimal) debit.getValue()[3]).isEqualByComparingTo("20");
        verify(jdbc).update(eq("update customer.customers set balance = balance + ? where id = ?"), eq(new BigDecimal("20.0000")), eq(customerId));
        verify(inventory).apply(eq(productId), argThat(value -> value.compareTo(new BigDecimal("-2")) == 0), eq("SALE"), eq(response.orderId()), eq("Order confirmation"));
        verify(jdbc, never()).update(contains("payment.payments"), any(Object[].class));
    }

    @Test
    void rejectsPreviousBalanceAboveTheLockedCustomerBalanceBeforeCreatingAnOrder() {
        authenticate("ORDER_CREATE");
        UUID customerId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE", "balance", new BigDecimal("100")));
        when(jdbc.queryForObject(eq("select balance from customer.customers where id = ? for update"), eq(BigDecimal.class), eq(customerId)))
            .thenReturn(new BigDecimal("30"));
        var request = new OrderConfirmationDtos.ConfirmationRequest("changed-balance", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null)), BigDecimal.ZERO, List.of(), null, new BigDecimal("40"));
        assertThatThrownBy(() -> service.confirm(request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("supera el saldo actual");
        verifyNoInteractions(inventory, pricing);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsNegativeOrOverprecisePreviousBalanceAmounts() {
        authenticate("ORDER_CREATE");
        for (String value : List.of("-1", "0.00001")) {
            var request = new OrderConfirmationDtos.ConfirmationRequest("invalid", UUID.randomUUID(), null,
                List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null)), BigDecimal.ZERO, List.of(), null, new BigDecimal(value));
            assertThatThrownBy(() -> service.confirm(request)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(jdbc, pricing, inventory);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sellerCannotConfirmWithAnotherPriceList(boolean assigned) {
        authenticate("ORDER_CREATE");
        UUID customerId = UUID.randomUUID();
        UUID allowedListId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), eq(customerId))).thenReturn(assigned
            ? Map.of("id", customerId, "status", "ACTIVE", "price_list_id", allowedListId)
            : Map.of("id", customerId, "status", "ACTIVE"));
        if (!assigned) when(jdbc.queryForObject(contains("code = 'GENERAL'"), eq(UUID.class))).thenReturn(allowedListId);
        var request = new OrderConfirmationDtos.ConfirmationRequest("wrong-list", customerId, UUID.randomUUID(),
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of());

        assertThatThrownBy(() -> service.confirm(request))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class)
            .hasMessageContaining("lista de precios");
        verifyNoInteractions(pricing, inventory, audit);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"assigned", "general", "admin"})
    void confirmsAuthorizedExplicitPriceList(String scenario) {
        authenticate(scenario.equals("admin") ? "ADMIN_ALL" : "ORDER_CREATE");
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), eq(customerId))).thenReturn(scenario.equals("general")
            ? Map.of("id", customerId, "status", "ACTIVE")
            : Map.of("id", customerId, "status", "ACTIVE", "price_list_id", scenario.equals("admin") ? UUID.randomUUID() : listId));
        if (scenario.equals("general")) when(jdbc.queryForObject(contains("code = 'GENERAL'"), eq(UUID.class))).thenReturn(listId);
        when(pricing.resolve(customerId, productId, listId)).thenReturn(Map.of(
            "priceListId", listId, "priceListCode", "GENERAL", "unitPrice", BigDecimal.TEN));

        var result = service.confirm(new OrderConfirmationDtos.ConfirmationRequest("allowed-list", customerId, listId,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of()));

        assertThat(result.total()).isEqualByComparingTo("10");
        verify(pricing).resolve(customerId, productId, listId);
        verify(inventory).apply(eq(productId), any(), eq("SALE"), eq(result.orderId()), anyString());
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
        assertThat((String) orderInsert.getValue()[9]).hasSize(64);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "40"})
    @SuppressWarnings({"unchecked", "rawtypes"})
    void sameFingerprintReturnsCommittedResponseWithoutResolvingPricingAgain(String previousBalance) throws Exception {
        authenticate("ORDER_CREATE");
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        AtomicInteger lookups = new AtomicInteger();
        AtomicReference<OrderConfirmationService.ConfirmationResult> committed = new AtomicReference<>();
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
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE", "balance", new BigDecimal("100")));
        when(jdbc.queryForObject(eq("select balance from customer.customers where id = ? for update"), eq(BigDecimal.class), eq(customerId)))
            .thenReturn(new BigDecimal("100"));
        when(pricing.resolve(customerId, productId, null)).thenReturn(Map.of(
            "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10")));

        var request = new OrderConfirmationDtos.ConfirmationRequest(
            "retry-key", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of(new OrderConfirmationDtos.PaymentRequest("CASH", new BigDecimal("10"))), null, new BigDecimal(previousBalance));
        var first = service.confirm(request);
        var fingerprint = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("idempotency_fingerprint"), fingerprint.capture());
        storedFingerprint.set((String) fingerprint.getValue()[9]);
        committed.set(first);

        var second = service.confirm(request);

        assertThat(second).isEqualTo(first);
        var changedDateRequest = new OrderConfirmationDtos.ConfirmationRequest(
            request.idempotencyKey(), customerId, null, request.lines(), request.orderDiscountPercent(),
            request.payments(), null, request.previousBalanceAmount(), LocalDate.of(2026, 9, 29));
        assertThatThrownBy(() -> service.confirm(changedDateRequest))
            .isInstanceOf(com.distribuidora.order.application.IdempotencyConflictException.class);
        assertThat(second.previousBalanceAmount()).isEqualByComparingTo(previousBalance);
        var changedBalanceRequest = new OrderConfirmationDtos.ConfirmationRequest(
            request.idempotencyKey(), customerId, null, request.lines(), request.orderDiscountPercent(),
            request.payments(), null, new BigDecimal("50"));
        assertThatThrownBy(() -> service.confirm(changedBalanceRequest))
            .isInstanceOf(com.distribuidora.order.application.IdempotencyConflictException.class);
        if (previousBalance.equals("0")) {
            var legacyRequest = new OrderConfirmationDtos.ConfirmationRequest(
                request.idempotencyKey(), customerId, null, request.lines(), request.orderDiscountPercent(), request.payments());
            assertThat(service.confirm(legacyRequest)).isEqualTo(first);
        }
        verify(pricing, times(1)).resolve(customerId, productId, null);

        var changedRequest = new OrderConfirmationDtos.ConfirmationRequest(
            request.idempotencyKey(), customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, new BigDecimal("2"), BigDecimal.ZERO, null)),
            request.orderDiscountPercent(), request.payments());
        assertThatThrownBy(() -> service.confirm(changedRequest))
            .isInstanceOf(com.distribuidora.order.application.IdempotencyConflictException.class);
        var changedSellerRequest = new OrderConfirmationDtos.ConfirmationRequest(
            request.idempotencyKey(), customerId, null, request.lines(), request.orderDiscountPercent(),
            request.payments(), UUID.randomUUID());
        assertThatThrownBy(() -> service.confirm(changedSellerRequest))
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
    void administratorCanSelectAnExistingSellerForTheOrder() {
        authenticate("ORDER_CREATE", "ADMIN_ALL");
        UUID customerId = UUID.randomUUID();
        UUID customerSellerId = UUID.randomUUID();
        UUID selectedSellerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE", "seller_id", customerSellerId));
        when(jdbc.queryForObject(contains("seller.seller_profiles"), eq(Boolean.class), eq(selectedSellerId)))
            .thenReturn(true);
        when(pricing.resolve(customerId, productId, null)).thenReturn(Map.of(
            "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10.0000")));

        service.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "admin-selected-seller", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of(), selectedSellerId));

        var orderInsert = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into orders.orders"), orderInsert.capture());
        assertThat(orderInsert.getValue()[3]).isEqualTo(selectedSellerId);
    }

    @Test
    void administratorDefaultsOrderSellerToCustomerAssignment() {
        authenticate("ORDER_CREATE", "ADMIN_ALL");
        UUID customerId = UUID.randomUUID();
        UUID customerSellerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE", "seller_id", customerSellerId));
        when(jdbc.queryForObject(contains("seller.seller_profiles"), eq(Boolean.class), eq(customerSellerId)))
            .thenReturn(true);
        when(pricing.resolve(customerId, productId, null)).thenReturn(Map.of(
            "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10.0000")));

        service.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "admin-default-seller", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of(), null));

        var orderInsert = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into orders.orders"), orderInsert.capture());
        assertThat(orderInsert.getValue()).contains(customerSellerId);
    }

    @Test
    void rejectsMissingSelectedSellerBeforePricingOrInventoryWrites() {
        authenticate("ORDER_CREATE", "ADMIN_ALL");
        UUID customerId = UUID.randomUUID();
        UUID selectedSellerId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE"));
        when(jdbc.queryForObject(contains("seller.seller_profiles"), eq(Boolean.class), eq(selectedSellerId)))
            .thenReturn(false);

        assertThatThrownBy(() -> service.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "missing-selected-seller", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of(), selectedSellerId)))
            .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);

        verifyNoInteractions(pricing, inventory);
        verify(jdbc, never()).update(contains("insert into orders.orders"), any(Object[].class));
    }

    @Test
    void nonAdminCannotOverrideAuthenticatedSellerProfile() {
        CurrentUserAccess currentUser = mock(CurrentUserAccess.class);
        OrderConfirmationService sellerService = new OrderConfirmationService(
            jdbc, pricing, calculation, inventory, audit, currentUser);
        UUID customerId = UUID.randomUUID();
        UUID profileSellerId = UUID.randomUUID();
        UUID requestedSellerId = UUID.randomUUID();
        when(currentUser.isAdmin()).thenReturn(false);
        when(currentUser.requireSellerProfile()).thenReturn(profileSellerId);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE"));

        assertThatThrownBy(() -> sellerService.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "unauthorized-seller-override", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of(), requestedSellerId)))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        verify(currentUser).requireSellerProfile();
        verifyNoInteractions(pricing, inventory);
    }

    @Test
    void nonAdminOrderUsesAuthenticatedSellerInsteadOfCustomerAssignment() {
        CurrentUserAccess currentUser = mock(CurrentUserAccess.class);
        OrderConfirmationService sellerService = new OrderConfirmationService(
            jdbc, pricing, calculation, inventory, audit, currentUser);
        UUID customerId = UUID.randomUUID();
        UUID customerSellerId = UUID.randomUUID();
        UUID profileSellerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(currentUser.isAdmin()).thenReturn(false);
        when(currentUser.requireSellerProfile()).thenReturn(profileSellerId);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE", "seller_id", customerSellerId));
        when(jdbc.queryForObject(contains("seller.seller_profiles"), eq(Boolean.class), eq(profileSellerId)))
            .thenReturn(true);
        when(pricing.resolve(customerId, productId, null)).thenReturn(Map.of(
            "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10.0000")));

        sellerService.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "authenticated-seller-default", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of(), null));

        var orderInsert = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into orders.orders"), orderInsert.capture());
        assertThat(orderInsert.getValue()).contains(profileSellerId).doesNotContain(customerSellerId);
    }

    @Test
    void rejectsNullRequestBeforeAnySideEffect() {
        assertThatThrownBy(() -> service.confirm(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("request");
        verifyNoInteractions(jdbc, pricing, inventory, audit);
    }

    @Test
    void appliesPersistedLineAndOrderDiscountRulesAndStoresRuleSnapshots() {
        authenticate("ORDER_CREATE");
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        UUID lineRuleId = UUID.randomUUID();
        UUID orderRuleId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE"));
        when(pricing.resolve(customerId, productId, null)).thenReturn(Map.of(
            "priceListId", listId, "priceListCode", "GENERAL", "unitPrice", new BigDecimal("100.0000")));
        when(discountRules.lineDiscount(customerId, productId, listId))
            .thenReturn(java.util.Optional.of(new CommercialDiscountRuleQueryService.DiscountSnapshot(
                lineRuleId, new BigDecimal("10.0000"))));
        when(discountRules.orderDiscount(customerId, null))
            .thenReturn(java.util.Optional.of(new CommercialDiscountRuleQueryService.DiscountSnapshot(
                orderRuleId, new BigDecimal("5.0000"))));

        var response = serviceWithDiscountRules.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "discount-rule-order", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of()));

        assertThat(response.total()).isEqualByComparingTo("85.5000");
        var orderInsert = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into orders.orders"), orderInsert.capture());
        assertThat(orderInsert.getValue()).contains(orderRuleId, new BigDecimal("5.0000"));
        var itemInserts = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(argThat(sql -> sql.contains("order_items") || sql.contains("sale_items")),
            itemInserts.capture());
        assertThat(itemInserts.getAllValues()).allSatisfy(args ->
            assertThat(java.util.Arrays.asList(args)).contains(lineRuleId));
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

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "40"})
    void editsRemittanceAmountWithoutChangingPaymentsOrDuplicatingDebt(String amount) {
        authenticate("ADMIN_ALL");
        UUID orderId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        when(jdbc.queryForMap(contains("from orders.orders"), eq(orderId))).thenReturn(Map.of(
            "order_status", "CONFIRMED", "sale_status", "CONFIRMED", "sale_id", saleId,
            "customer_id", customerId, "sale_total", BigDecimal.TEN, "paid", new BigDecimal("2"),
            "previous_balance_amount", new BigDecimal("30")));
        when(jdbc.queryForMap(contains("customer.customers"), eq(customerId)))
            .thenReturn(Map.of("id", customerId, "balance", new BigDecimal("100")));
        when(jdbc.queryForList(contains("from orders.order_items"), eq(orderId)))
            .thenReturn(List.of(Map.of("product_id", productId, "quantity", BigDecimal.ONE)));
        when(jdbc.queryForObject(contains("account_ledger"), eq(BigDecimal.class), eq(saleId)))
            .thenReturn(new BigDecimal("8"));
        when(jdbc.queryForObject(contains("catalog.products"), eq(String.class), eq(productId)))
            .thenReturn("Product");
        when(pricing.resolve(customerId, productId, listId)).thenReturn(Map.of(
            "priceListId", listId, "priceListCode", "GENERAL", "unitPrice", BigDecimal.TEN));

        var result = service.editConfirmed(orderId, new OrderEditDtos.EditRequest(listId,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, amount == null ? null : new BigDecimal(amount)));

        assertThat(result.total()).isEqualByComparingTo("10");
        assertThat(result.paid()).isEqualByComparingTo("2");
        assertThat(result.balance()).isEqualByComparingTo("8");
        var update = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("update orders.orders set subtotal"), update.capture());
        assertThat((BigDecimal) update.getValue()[5]).isEqualByComparingTo(amount == null ? "30" : amount);
        verify(jdbc, never()).update(contains("account_ledger"), any(Object[].class));
        verify(jdbc, never()).update(contains("customer.customers"), any(Object[].class));
        verify(jdbc, never()).update(contains("payment.payments"), any(Object[].class));
        verifyNoInteractions(inventory);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "101", "1.00001"})
    void rejectsInvalidRemittanceEditsBeforeChangingCommercialData(String amount) {
        authenticate("ADMIN_ALL");
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(jdbc.queryForMap(contains("from orders.orders"), eq(orderId))).thenReturn(Map.of(
            "order_status", "CONFIRMED", "sale_status", "CONFIRMED", "customer_id", customerId,
            "previous_balance_amount", new BigDecimal("30")));
        when(jdbc.queryForMap(contains("customer.customers"), eq(customerId)))
            .thenReturn(Map.of("id", customerId, "balance", new BigDecimal("100")));

        assertThatThrownBy(() -> service.editConfirmed(orderId, new OrderEditDtos.EditRequest(null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, new BigDecimal(amount))))
            .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(pricing, inventory, audit);
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

    private OrderConfirmationService.ConfirmationResult confirmWithPayments(
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
