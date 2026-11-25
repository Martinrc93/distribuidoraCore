package com.distribuidora.order;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.order.api.OrderConfirmationDtos;
import com.distribuidora.order.application.OrderCalculationService;
import com.distribuidora.order.application.OrderConfirmationService;
import com.distribuidora.pricing.application.PricingQueryService;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.distribuidora.shared.security.TestCurrentUserAccess;

class OrderConfirmationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PricingQueryService pricing = mock(PricingQueryService.class);
    private final OrderCalculationService calculation = new OrderCalculationService();
    private final InventoryMovementService inventory = mock(InventoryMovementService.class);
    private final AuditService audit = mock(AuditService.class);
    private final OrderConfirmationService service = new OrderConfirmationService(jdbc, pricing, calculation, inventory, audit, TestCurrentUserAccess.admin(jdbc));

    @BeforeEach
    void numberOrders() {
        org.mockito.Mockito.lenient().when(jdbc.queryForObject(eq("select status from catalog.products where id = ?"), eq(String.class), any(UUID.class))).thenReturn("ACTIVE");
        org.mockito.Mockito.lenient().when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("from catalog.products where id in"), any(Object[].class)))
            .thenAnswer(inv -> {
                Object[] args = inv.getArguments();
                java.util.List<java.util.Map<String, Object>> rows = new java.util.ArrayList<>();
                for (int i = 1; i < args.length; i++) {
                    Object arg = args[i];
                    if (arg instanceof Object[] nested) {
                        for (Object item : nested) {
                            rows.add(java.util.Map.of("id", item, "name", "Producto", "status", "ACTIVE"));
                        }
                    } else if (arg != null) {
                        rows.add(java.util.Map.of("id", arg, "name", "Producto", "status", "ACTIVE"));
                    }
                }
                return rows;
            });
        when(jdbc.queryForObject(eq("select orders.next_customer_order_number(?)"), eq(String.class), any(UUID.class)))
            .thenReturn("000100004");
    }

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
        when(pricing.resolveBatch(customerId, List.of(productId), null)).thenReturn(List.of(Map.of(
            "productId", productId, "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10"))));
        Instant before = Instant.now();
        service.confirm(new OrderConfirmationDtos.ConfirmationRequest("dated-order", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of(), null, BigDecimal.ZERO, chosenDate == null ? null : LocalDate.parse(chosenDate)));
        Instant after = Instant.now();
        var orderInsert = org.mockito.ArgumentCaptor.forClass(Object[].class);
        var saleInsert = org.mockito.ArgumentCaptor.forClass(Object[].class);
        var ledgerInsert = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into orders.orders"), orderInsert.capture());
        assertThat(orderInsert.getValue()[1]).isEqualTo("000100004");
        verify(jdbc).update(contains("insert into sale.sales"), saleInsert.capture());
        assertThat(saleInsert.getValue()[1]).isEqualTo(orderInsert.getValue()[1]);
        verify(jdbc).update(contains("insert into customer.account_ledger"), ledgerInsert.capture());
        Timestamp registeredAt = (Timestamp) orderInsert.getValue()[7];
        Timestamp ledgerAt = (Timestamp) ledgerInsert.getValue()[4];
        assertThat(saleInsert.getValue()[6]).isEqualTo(registeredAt);
        // Without a reseller, the recipient is the account that owes the sale and receives its debit.
        assertThat(saleInsert.getValue()[3]).isEqualTo(customerId);
        assertThat(saleInsert.getValue()[8]).isEqualTo(customerId);
        assertThat(ledgerInsert.getValue()[1]).isEqualTo(customerId);
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
        when(pricing.resolveBatch(customerId, List.of(productId), null)).thenReturn(List.of(Map.of("productId", productId, "priceListId", listId, "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10"))));
        var response = service.confirm(new OrderConfirmationDtos.ConfirmationRequest("previous-balance", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, new BigDecimal("2"), BigDecimal.ZERO, null)), BigDecimal.ZERO, List.of(), null, new BigDecimal("40")));
        assertThat(response.total()).isEqualByComparingTo("20");
        assertThat(response.balance()).isEqualByComparingTo("20");
        assertThat(response.previousBalanceAmount()).isEqualByComparingTo("40");
        var insert = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("previous_balance_amount"), insert.capture());
        // previous_balance_amount precedes created_by_user_id and created_by_admin.
        assertThat((BigDecimal) insert.getValue()[insert.getValue().length - 3]).isEqualByComparingTo("40");
        assertThat(insert.getValue()[insert.getValue().length - 1]).isEqualTo(false);
        var debit = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into customer.account_ledger"), debit.capture());
        assertThat((BigDecimal) debit.getValue()[3]).isEqualByComparingTo("20");
        verify(jdbc).update(eq("update customer.customers set balance = balance + ? where id = ?"), eq(new BigDecimal("20.0000")), eq(customerId));
        verifyNoInteractions(inventory);
        verify(jdbc, never()).update(contains("payment.payments"), any(Object[].class));
    }

    @Test
    void rejectsPreviousBalanceAboveTheLockedCustomerBalanceBeforeCreatingAnOrder() {
        authenticate("ORDER_CREATE");
        UUID customerId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE", "balance", new BigDecimal("30")));
        var request = new OrderConfirmationDtos.ConfirmationRequest("changed-balance", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null)), BigDecimal.ZERO, List.of(), null, new BigDecimal("40"));
        assertThatThrownBy(() -> service.confirm(request)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("supera el saldo actual");
        verifyNoInteractions(inventory, pricing);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsInvalidPreviousDebtPaymentsAndMisplacedTransferReferencesBeforeSideEffects() {
        authenticate("ORDER_CREATE");
        var line = List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null));
        for (var previous : List.of(
            new OrderConfirmationDtos.PaymentRequest("CUSTOMER_ACCOUNT", BigDecimal.ONE),
            new OrderConfirmationDtos.PaymentRequest("CASH", BigDecimal.ONE, "TR-1"),
            new OrderConfirmationDtos.PaymentRequest("CASH", new BigDecimal("0.00001")))) {
            var request = new OrderConfirmationDtos.ConfirmationRequest("invalid-previous", UUID.randomUUID(), null, line,
                BigDecimal.ZERO, List.of(), null, null, null, List.of(previous));
            assertThatThrownBy(() -> service.confirm(request)).isInstanceOf(IllegalArgumentException.class);
        }
        var cashWithReference = new OrderConfirmationDtos.ConfirmationRequest("invalid-reference", UUID.randomUUID(), null, line,
            BigDecimal.ZERO, List.of(new OrderConfirmationDtos.PaymentRequest("CASH", BigDecimal.ONE, "TR-2")));
        assertThatThrownBy(() -> service.confirm(cashWithReference)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc, pricing, inventory);
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
        if (!assigned) when(jdbc.queryForObject(contains("is_default = true"), eq(UUID.class))).thenReturn(allowedListId);
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
    @ValueSource(strings = {"assigned", "general", "admin", "permission"})
    void confirmsAuthorizedExplicitPriceList(String scenario) {
        if (scenario.equals("permission")) authenticate("ORDER_CREATE", "ORDER_PRICE_LIST_CHANGE");
        else authenticate(scenario.equals("admin") ? "ADMIN_ALL" : "ORDER_CREATE");
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), eq(customerId))).thenReturn(scenario.equals("general")
            ? Map.of("id", customerId, "status", "ACTIVE")
            : Map.of("id", customerId, "status", "ACTIVE", "price_list_id", scenario.equals("admin") || scenario.equals("permission") ? UUID.randomUUID() : listId));
        if (scenario.equals("general")) when(jdbc.queryForObject(contains("is_default = true"), eq(UUID.class))).thenReturn(listId);
        when(pricing.resolveBatch(customerId, List.of(productId), listId)).thenReturn(List.of(Map.of(
            "productId", productId, "priceListId", listId, "priceListCode", "GENERAL", "unitPrice", BigDecimal.TEN)));

        var result = service.confirm(new OrderConfirmationDtos.ConfirmationRequest("allowed-list", customerId, listId,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of()));

        assertThat(result.total()).isEqualByComparingTo("10");
        verify(pricing).resolveBatch(customerId, List.of(productId), listId);
        verifyNoInteractions(inventory);
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
        when(pricing.resolveBatch(customerId, List.of(productId), null)).thenReturn(List.of(Map.of(
            "productId", productId, "priceListId", listId, "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10.0000"))));

        var response = service.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "key-1", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, new BigDecimal("2.0"), BigDecimal.ZERO, null)),
            BigDecimal.ZERO,
            List.of(new OrderConfirmationDtos.PaymentRequest("CASH", new BigDecimal("20.00")))));

        assertThat(response.orderId()).isNotNull();
        assertThat(response.saleId()).isNotNull();
        assertThat(response.total()).isEqualByComparingTo("20.0000");
        assertThat(response.paid()).isEqualByComparingTo("20.0000");
        verifyNoInteractions(inventory);
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
        when(pricing.resolveBatch(customerId, List.of(productId), null)).thenReturn(List.of(Map.of(
            "productId", productId, "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10"))));

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
        assertThat(second.saleNumber()).isEqualTo(second.orderNumber());
        verify(jdbc, never()).queryForObject("select sale.next_sale_number()", String.class);
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
        verify(pricing, times(1)).resolveBatch(customerId, List.of(productId), null);

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
        verify(pricing, times(1)).resolveBatch(customerId, List.of(productId), null);
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

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN_ALL", "ORDER_DISCOUNT"})
    void allowsLineAndOrderDiscountsWithTheirOwnPermission(String authority) {
        authenticate("ORDER_CREATE", authority);
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE"));
        when(pricing.resolveBatch(customerId, List.of(productId), null)).thenReturn(List.of(Map.of(
            "productId", productId, "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10.0000"))));

        var response = service.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "key-admin-discounts", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, new BigDecimal("5.00"), null)),
            new BigDecimal("5.00"), List.of()));

        assertThat(response.total()).isEqualByComparingTo("9.0250");
        verifyNoInteractions(inventory);
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
        when(jdbc.queryForObject(contains("seller.seller_profiles"), eq(String.class), eq(selectedSellerId)))
            .thenReturn("ACTIVE");
        when(pricing.resolveBatch(customerId, List.of(productId), null)).thenReturn(List.of(Map.of(
            "productId", productId, "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10.0000"))));

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
        when(jdbc.queryForObject(contains("seller.seller_profiles"), eq(String.class), eq(customerSellerId)))
            .thenReturn("ACTIVE");
        when(pricing.resolveBatch(customerId, List.of(productId), null)).thenReturn(List.of(Map.of(
            "productId", productId, "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10.0000"))));

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
        when(jdbc.queryForObject(contains("seller.seller_profiles"), eq(String.class), eq(selectedSellerId)))
            .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

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
        when(jdbc.queryForObject(contains("seller.seller_profiles"), eq(String.class), eq(profileSellerId)))
            .thenReturn("ACTIVE");
        when(pricing.resolveBatch(customerId, List.of(productId), null)).thenReturn(List.of(Map.of(
            "productId", productId, "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10.0000"))));

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
    void zeroDiscountsUseTheFullPriceWithoutAutomaticAdjustments() {
        authenticate("ORDER_CREATE");
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE"));
        when(pricing.resolveBatch(customerId, List.of(productId), null)).thenReturn(List.of(Map.of(
            "productId", productId, "priceListId", listId, "priceListCode", "GENERAL", "unitPrice", new BigDecimal("100.0000"))));
        var response = service.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "full-price-order", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of()));

        assertThat(response.total()).isEqualByComparingTo("100.0000");
        var orderInsert = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("insert into orders.orders"), orderInsert.capture());
        assertThat((BigDecimal) orderInsert.getValue()[5]).isEqualByComparingTo("0");
        assertThat((BigDecimal) orderInsert.getValue()[13]).isEqualByComparingTo("0");
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<Object[]>> itemInserts = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(jdbc, times(2)).batchUpdate(argThat(sql -> sql.contains("order_items") || sql.contains("sale_items")),
            itemInserts.capture());
        assertThat(itemInserts.getAllValues()).flatExtracting(rows -> rows).allSatisfy(args ->
            assertThat((BigDecimal) args[9]).isEqualByComparingTo("0"));
        verify(jdbc, never()).queryForList(contains("commercial_discount_rules"), any(Object[].class));
    }

    @Test
    void confirmedOrderEditRequiresItsOwnPermissionBeforeReadingOrChangingData() {
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
            "customer_id", customerId, "account_customer_id", customerId, "sale_total", BigDecimal.TEN,
            "paid", new BigDecimal("2"), "previous_balance_amount", new BigDecimal("30")));
        when(jdbc.queryForMap(contains("customer.customers"), eq(customerId)))
            .thenReturn(Map.of("id", customerId, "balance", new BigDecimal("100")));
        when(jdbc.queryForList(contains("from orders.order_items"), eq(orderId)))
            .thenReturn(List.of(Map.of("product_id", productId, "quantity", BigDecimal.ONE)));
        when(jdbc.queryForObject(contains("account_ledger"), eq(BigDecimal.class), eq(saleId)))
            .thenReturn(new BigDecimal("8"));
        when(pricing.resolveBatch(customerId, List.of(productId), listId)).thenReturn(List.of(Map.of(
            "productId", productId, "priceListId", listId, "priceListCode", "GENERAL", "unitPrice", BigDecimal.TEN)));

        var result = service.editConfirmed(orderId, new OrderEditDtos.EditRequest(listId,
            List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, amount == null ? null : new BigDecimal(amount)));

        assertThat(result.total()).isEqualByComparingTo("10");
        assertThat(result.paid()).isEqualByComparingTo("2");
        assertThat(result.balance()).isEqualByComparingTo("8");
        var update = org.mockito.ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("update orders.orders set subtotal"), update.capture());
        assertThat((BigDecimal) update.getValue()[4]).isEqualByComparingTo(amount == null ? "30" : amount);
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
            "account_customer_id", customerId, "previous_balance_amount", new BigDecimal("30")));
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
    void rejectsForeignCustomerBeforeLockingIdempotencyKey() {
        authenticate("ORDER_CREATE");
        CurrentUserAccess currentUser = mock(CurrentUserAccess.class);
        OrderConfirmationService scopedService = new OrderConfirmationService(
            jdbc, pricing, calculation, inventory, audit, currentUser);
        UUID customerId = UUID.randomUUID();
        doThrow(new org.springframework.dao.EmptyResultDataAccessException(1))
            .when(currentUser).requireCustomerAccess(customerId);

        assertThatThrownBy(() -> scopedService.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "foreign-key", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of())))
            .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);

        verifyNoInteractions(jdbc, pricing, inventory, audit);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void rejectsIdempotencyKeyOwnedByForeignOrderWithoutRevealingConflictOrOrder() throws Exception {
        authenticate("ORDER_CREATE");
        CurrentUserAccess currentUser = mock(CurrentUserAccess.class);
        OrderConfirmationService scopedService = new OrderConfirmationService(
            jdbc, pricing, calculation, inventory, audit, currentUser);
        UUID customerId = UUID.randomUUID();
        UUID foreignOrderId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq("shared-key"))).thenAnswer(invocation -> {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("order_id", UUID.class)).thenReturn(foreignOrderId);
            when(rs.getObject("sale_id", UUID.class)).thenReturn(UUID.randomUUID());
            when(rs.getObject("customer_id", UUID.class)).thenReturn(UUID.randomUUID());
            when(rs.getString("order_number")).thenReturn("000100001");
            when(rs.getString("sale_number")).thenReturn("000100001");
            when(rs.getBigDecimal("total")).thenReturn(BigDecimal.TEN);
            when(rs.getBigDecimal("paid")).thenReturn(BigDecimal.ZERO);
            when(rs.getString("idempotency_fingerprint")).thenReturn("different-fingerprint");
            return List.of(((RowMapper) invocation.getArgument(1)).mapRow(rs, 0));
        });
        doThrow(new org.springframework.dao.EmptyResultDataAccessException(1))
            .when(currentUser).requireOrderAccess(foreignOrderId);

        assertThatThrownBy(() -> scopedService.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "shared-key", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of())))
            .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);

        verify(currentUser).requireCustomerAccess(customerId);
        verify(currentUser).requireOrderAccess(foreignOrderId);
    }

    @Test
    void rejectsPreviousDebtPaymentsWithoutSalePaymentPermissionBeforeSideEffects() {
        authenticate("ORDER_CREATE");
        var request = new OrderConfirmationDtos.ConfirmationRequest(
            "prev-debt-no-perm", UUID.randomUUID(), null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of(), null, BigDecimal.TEN, null,
            List.of(new OrderConfirmationDtos.PaymentRequest("CASH", BigDecimal.TEN)));

        assertThatThrownBy(() -> service.confirm(request))
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
        when(pricing.resolveBatch(any(), any(), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked") List<UUID> productIds = (List<UUID>) inv.getArgument(1);
            return productIds.stream().map(id -> Map.<String, Object>of(
                "productId", id, "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("5.0000"))).toList();
        });
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
    void editConfirmedExcludesCurrentSaleDebtFromAvailablePreviousBalance() {
        authenticate("ADMIN_ALL");
        UUID orderId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        when(jdbc.queryForMap(contains("from orders.orders"), eq(orderId))).thenReturn(Map.of(
            "order_status", "CONFIRMED", "sale_status", "CONFIRMED", "sale_id", saleId,
            "customer_id", customerId, "account_customer_id", customerId, "sale_total", BigDecimal.TEN,
            "paid", new BigDecimal("2"), "previous_balance_amount", BigDecimal.ZERO));
        when(jdbc.queryForMap(contains("customer.customers"), eq(customerId)))
            .thenReturn(Map.of("id", customerId, "balance", new BigDecimal("100")));
        when(jdbc.queryForObject(contains("account_ledger"), eq(BigDecimal.class), eq(saleId)))
            .thenReturn(new BigDecimal("8"));
        when(jdbc.queryForList(contains("from orders.order_items"), eq(orderId)))
            .thenReturn(List.of(Map.of("product_id", productId, "quantity", BigDecimal.ONE)));
        when(pricing.resolveBatch(customerId, List.of(productId), listId)).thenReturn(List.of(Map.of(
            "productId", productId, "priceListId", listId, "priceListCode", "GENERAL", "unitPrice", BigDecimal.TEN)));

        assertThatThrownBy(() -> service.editConfirmed(orderId, new OrderEditDtos.EditRequest(
            listId, List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, new BigDecimal("93"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("supera el saldo actual");

        var accepted = service.editConfirmed(orderId, new OrderEditDtos.EditRequest(
            listId, List.of(new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, new BigDecimal("92")));
        assertThat(accepted.total()).isEqualByComparingTo("10");
    }

    @Test
    void rejectsInactiveAssignedSellerBeforePricingOrInventoryWrites() {
        authenticate("ORDER_CREATE", "ADMIN_ALL");
        UUID customerId = UUID.randomUUID();
        UUID inactiveSellerId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(Object[].class)))
            .thenReturn(List.of());
        when(jdbc.queryForMap(contains("customer.customers"), any(Object[].class)))
            .thenReturn(Map.of("id", customerId, "status", "ACTIVE", "seller_id", inactiveSellerId));
        when(jdbc.queryForObject(contains("seller.seller_profiles"), eq(String.class), eq(inactiveSellerId)))
            .thenReturn("INACTIVE");

        assertThatThrownBy(() -> service.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "inactive-seller", customerId, null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null)),
            BigDecimal.ZERO, List.of(), null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no está activo");
        verifyNoInteractions(pricing, inventory);
    }

    @Test
    void editConfirmedAllowsDefaultFallbackSnapshotsAndPreventsCloningOverridesToDuplicateLines() {
        authenticate("ORDER_EDIT");
        UUID orderId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID assignedListId = UUID.randomUUID();
        UUID defaultListId = UUID.randomUUID();
        when(jdbc.queryForMap(contains("from orders.orders"), eq(orderId))).thenReturn(Map.of(
            "order_status", "CONFIRMED", "sale_status", "CONFIRMED", "sale_id", saleId,
            "customer_id", customerId, "account_customer_id", customerId, "sale_total", new BigDecimal("50.0000"),
            "paid", BigDecimal.ZERO, "previous_balance_amount", BigDecimal.ZERO, "order_discount_percent", BigDecimal.ZERO));
        when(jdbc.queryForMap(contains("customer.customers"), eq(customerId)))
            .thenReturn(Map.of("id", customerId, "balance", BigDecimal.ZERO));
        when(jdbc.queryForObject(eq("select price_list_id from customer.customers where id = ?"), eq(UUID.class), eq(customerId)))
            .thenReturn(assignedListId);
        when(jdbc.queryForObject(eq("select id from catalog.price_lists where is_default = true and status = 'ACTIVE'"), eq(UUID.class)))
            .thenReturn(defaultListId);
        when(jdbc.queryForList(contains("from orders.order_items"), eq(orderId)))
            .thenReturn(List.of(Map.of("product_id", productId, "quantity", BigDecimal.ONE,
                "price_list_id", defaultListId, "unit_price", new BigDecimal("50.0000"), "line_discount_percent", BigDecimal.ZERO)));
        when(pricing.resolveBatch(eq(customerId), any(), isNull())).thenReturn(List.of(Map.of(
            "productId", productId, "priceListId", defaultListId, "priceListCode", "GENERAL", "unitPrice", new BigDecimal("80.0000"))));

        // Single line preserving the historical $50 override succeeds even when snapshot used default list fallback
        var single = service.editConfirmed(orderId, new OrderEditDtos.EditRequest(
            null, List.of(new OrderConfirmationDtos.LineRequest(productId, new BigDecimal("2"), BigDecimal.ZERO, new BigDecimal("50.0000"))),
            BigDecimal.ZERO, BigDecimal.ZERO));
        assertThat(single.total()).isEqualByComparingTo("100.0000");

        // Second duplicate line cannot clone that same historical override without ORDER_PRICE_OVERRIDE
        when(pricing.resolveBatch(eq(customerId), any(), isNull())).thenReturn(List.of(
            Map.of("productId", productId, "priceListId", defaultListId, "priceListCode", "GENERAL", "unitPrice", new BigDecimal("80.0000")),
            Map.of("productId", productId, "priceListId", defaultListId, "priceListCode", "GENERAL", "unitPrice", new BigDecimal("80.0000"))));
        assertThatThrownBy(() -> service.editConfirmed(orderId, new OrderEditDtos.EditRequest(
            null, List.of(
                new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("50.0000")),
                new OrderConfirmationDtos.LineRequest(productId, BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("50.0000"))),
            BigDecimal.ZERO, BigDecimal.ZERO)))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void rejectsOverSizedLineAndPaymentListsAndOverflowAmounts() {
        var tooManyLines = java.util.Collections.nCopies(501,
            new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO, null));
        assertThatThrownBy(() -> service.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "too-many-lines", UUID.randomUUID(), null, tooManyLines, BigDecimal.ZERO, List.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("500");

        var tooManyPayments = java.util.Collections.nCopies(51,
            new OrderConfirmationDtos.PaymentRequest("CASH", BigDecimal.ONE));
        assertThatThrownBy(() -> service.confirm(validRequest(tooManyPayments)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("50");

        assertThatThrownBy(() -> service.confirm(new OrderConfirmationDtos.ConfirmationRequest(
            "overflow-price", UUID.randomUUID(), null,
            List.of(new OrderConfirmationDtos.LineRequest(UUID.randomUUID(), BigDecimal.ONE, BigDecimal.ZERO,
                new BigDecimal("1000000000000000"))),
            BigDecimal.ZERO, List.of())))
            .isInstanceOf(IllegalArgumentException.class);
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
        when(pricing.resolveBatch(customerId, List.of(productId), null)).thenReturn(List.of(Map.of(
            "productId", productId, "priceListId", UUID.randomUUID(), "priceListCode", "GENERAL", "unitPrice", new BigDecimal("10.0000"))));
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
