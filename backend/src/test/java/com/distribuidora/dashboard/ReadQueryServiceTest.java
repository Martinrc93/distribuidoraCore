package com.distribuidora.dashboard;

import com.distribuidora.dashboard.application.ReadQueryService;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.web.PageResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadQueryServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ReadQueryService service = new ReadQueryService(jdbc);

    @Test
    void includesSkuInProductProjectionForAdminAndSellerQueries() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Number.class), any(Object[].class))).thenReturn(0);

        service.products(0, 20, "");
        CurrentUserAccess sellerAccess = mock(CurrentUserAccess.class);
        when(sellerAccess.isAdmin()).thenReturn(false);
        when(sellerAccess.requireSellerProfile()).thenReturn(UUID.randomUUID());
        new ReadQueryService(jdbc, sellerAccess).products(0, 20, "");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues()).allSatisfy(query -> {
            assertThat(query).contains("p.sku");
            assertThat(query).doesNotContain("p.price");
        });
        assertThat(sql.getAllValues().get(0)).contains("p.cost");
        assertThat(sql.getAllValues().get(1)).doesNotContain("p.cost");
    }

    @Test
    void userAdministrationProjectionIncludesRolesAndSellerProfile() {
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Number.class), any(Object[].class))).thenReturn(0);

        service.users(0, 20, "mateo");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), parameters.capture());
        assertThat(sql.getValue()).contains("string_agg(r.code", "sellerDisplayName", "identity.user_roles");
        assertThat(parameters.getValue()[0]).isEqualTo("%mateo%");
    }

    @Test
    void returnsPagedMovementsForProductOrderedByNewest() {
        UUID productId = UUID.randomUUID();
        Map<String, Object> movement = new HashMap<>();
        movement.put("id", UUID.randomUUID());
        movement.put("movementType", "MANUAL_ADJUSTMENT");
        movement.put("quantity", "2.0");
        movement.put("reason", "Reposición");
        movement.put("referenceType", null);
        movement.put("referenceId", null);
        movement.put("date", Instant.parse("2026-09-18T12:00:00Z"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(movement));
        when(jdbc.queryForObject(
            eq("select count(*) from inventory.stock_movements where product_id = ?"),
            eq(Number.class), eq(productId)))
            .thenReturn(3);

        PageResponse<Map<String, Object>> response = service.movements(productId, 1, 2);

        assertThat(response.content()).containsExactly(movement);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.totalPages()).isEqualTo(2);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), parameters.capture());
        assertThat(sql.getValue()).contains(
            "sm.id",
            "sm.movement_type as \"movementType\"",
            "sm.quantity",
            "sm.reason",
            "sm.reference_type as \"referenceType\"",
            "sm.reference_id as \"referenceId\"",
            "sm.created_at as date",
            "where sm.product_id = ?",
            "order by sm.created_at desc",
            "limit ? offset ?"
        );
        assertThat(parameters.getValue()).containsExactly(productId, 2, 2);
        verify(jdbc).queryForObject(
            eq("select count(*) from inventory.stock_movements where product_id = ?"),
            eq(Number.class), eq(productId));
    }

    @Test
    void returnsPersistedOrderDetailWithSnapshotsAndAccountTotals() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        Map<String, Object> order = Map.of(
            "id", orderId,
            "number", "ORD-1",
            "customerId", customerId,
            "customer", "Acme",
            "status", "CONFIRMED",
            "subtotal", "20.0000",
            "discount", "2.0000",
            "total", "18.0000"
        );
        Map<String, Object> item = Map.of(
            "productId", UUID.randomUUID(),
            "productName", "Producto",
            "quantity", "2.0000",
            "unitPrice", "10.0000",
            "lineTotal", "18.0000",
            "priceListId", UUID.randomUUID(),
            "priceListCode", "GENERAL",
            "lineDiscountPercent", "10.0000"
        );
        Map<String, Object> sale = Map.of(
            "id", saleId,
            "number", "SAL-1",
            "status", "CONFIRMED",
            "total", "18.0000",
            "paid", "10.0000",
            "balance", "8.0000"
        );
        Map<String, Object> payment = Map.of("amount", "10.0000", "method", "CASH");
        Map<String, Object> account = Map.of("debit", "8.0000", "credit", "0.0000", "net", "8.0000");
        when(jdbc.queryForMap(contains("from orders.orders"), eq(orderId))).thenReturn(order);
        when(jdbc.queryForList(contains("from orders.order_items"), eq(orderId))).thenReturn(List.of(item));
        when(jdbc.queryForMap(contains("from sale.sales"), eq(orderId))).thenReturn(sale);
        when(jdbc.queryForList(contains("from payment.payments"), eq(saleId))).thenReturn(List.of(payment));
        when(jdbc.queryForMap(contains("from customer.account_ledger"), eq(customerId), eq(saleId)))
            .thenReturn(account);

        Map<String, Object> response = service.orderDetail(orderId);

        assertThat(response).containsEntry("order", order).containsEntry("sale", sale)
            .containsEntry("items", List.of(item)).containsEntry("payments", List.of(payment))
            .containsEntry("account", account);
    }

    @Test
    void rejectsMissingOrderDetail() {
        UUID orderId = UUID.randomUUID();
        when(jdbc.queryForMap(contains("from orders.orders"), eq(orderId)))
            .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> service.orderDetail(orderId))
            .isInstanceOf(org.springframework.dao.EmptyResultDataAccessException.class);
    }
}
