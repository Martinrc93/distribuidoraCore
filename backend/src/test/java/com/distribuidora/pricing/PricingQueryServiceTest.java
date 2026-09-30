package com.distribuidora.pricing;

import com.distribuidora.pricing.application.PricingQueryService;
import com.distribuidora.shared.web.PageResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PricingQueryServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PricingQueryService service = new PricingQueryService(jdbc);

    @Test
    void rejectsUnboundedOrInvalidBatchesBeforeQuerying() {
        UUID customerId = UUID.randomUUID();
        for (List<UUID> ids : List.of(List.<UUID>of(), java.util.Collections.nCopies(101, UUID.randomUUID()), java.util.Arrays.asList((UUID) null))) {
            assertThatThrownBy(() -> service.resolveBatch(customerId, ids, null)).isInstanceOf(IllegalArgumentException.class);
        }
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void resolvesDistinctProductsInOneQueryWithTheSameListFallbackOrderAndEffectiveDate() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        var price = Map.<String, Object>of("productId", productId, "priceListId", listId, "priceListCode", "GENERAL", "unitPrice", BigDecimal.ZERO);
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of("price_list_id", listId), Map.of("id", listId, "status", "ACTIVE"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(price));
        assertThat(service.resolveBatch(customerId, List.of(productId, productId), null)).containsExactly(price);
        var sql = ArgumentCaptor.forClass(String.class);
        var parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), parameters.capture());
        assertThat(parameters.getValue()).containsExactly(listId, listId, listId, productId);
        assertThat(sql.getValue()).contains("candidate.status = 'ACTIVE'", "candidate.id < ?", "order by (candidate.id = ?) desc, candidate.id desc", "America/Argentina/Buenos_Aires", "h.effective_on desc, h.created_at desc, h.id desc");
    }

    @Test
    void returnsPagedPriceLists() {
        Map<String, Object> list = Map.of("id", UUID.randomUUID(), "code", "GENERAL", "name", "Lista general");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(list));
        when(jdbc.queryForObject(anyString(), eq(Number.class), any(Object[].class))).thenReturn(3);

        PageResponse<Map<String, Object>> response = service.lists(1, 2);

        assertThat(response.content()).containsExactly(list);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.totalPages()).isEqualTo(2);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("created_at as \"createdAt\"")
            .contains("updated_at as \"updatedAt\"");
    }

    @Test
    void returnsPagedPricesForAList() {
        UUID listId = UUID.randomUUID();
        Map<String, Object> price = Map.of("productId", UUID.randomUUID(), "sku", "SKU-1", "price", new BigDecimal("10.2500"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(price));
        when(jdbc.queryForObject(anyString(), eq(Number.class), any(Object[].class))).thenReturn(4);

        PageResponse<Map<String, Object>> response = service.prices(listId, 0, 20);

        assertThat(response.content()).containsExactly(price);
        assertThat(response.totalElements()).isEqualTo(4);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("\"effectiveOn\"")
            .contains("catalog.product_price_history");
    }

    @Test
    void resolvesUsingCustomerAssignedList() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID assignedListId = UUID.randomUUID();
        Map<String, Object> customer = new HashMap<>();
        customer.put("price_list_id", assignedListId);
        Map<String, Object> list = Map.of("id", assignedListId, "code", "LISTA_2", "status", "ACTIVE");
        Map<String, Object> productPrice = Map.of("priceListId", assignedListId, "priceListCode", "LISTA_2",
            "productId", productId, "unitPrice", new BigDecimal("12.5000"));
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(customer, list, productPrice);

        Map<String, Object> result = service.resolve(customerId, productId, null);

        assertThat(result).containsEntry("priceListId", assignedListId)
            .containsEntry("priceListCode", "LISTA_2")
            .containsEntry("productId", productId)
            .containsEntry("unitPrice", new BigDecimal("12.5000"));
    }

    @Test
    void fallsBackToActiveGeneralWhenCustomerHasNoAssignedList() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID generalId = UUID.randomUUID();
        Map<String, Object> customer = new HashMap<>();
        customer.put("price_list_id", null);
        Map<String, Object> general = Map.of("id", generalId, "code", "GENERAL", "status", "ACTIVE");
        Map<String, Object> productPrice = Map.of("priceListId", generalId, "priceListCode", "GENERAL",
            "productId", productId, "unitPrice", new BigDecimal("9.9900"));
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(customer, general, productPrice);

        Map<String, Object> result = service.resolve(customerId, productId, null);

        assertThat(result).containsEntry("priceListCode", "GENERAL");
    }

    @Test
    void resolvesUsingExplicitActiveListBeforeCustomerAssignment() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID explicitListId = UUID.randomUUID();
        Map<String, Object> customer = new HashMap<>();
        customer.put("price_list_id", UUID.randomUUID());
        Map<String, Object> list = Map.of("id", explicitListId, "code", "LISTA_3", "status", "ACTIVE");
        Map<String, Object> productPrice = Map.of("priceListId", explicitListId, "priceListCode", "LISTA_3",
            "productId", productId, "unitPrice", BigDecimal.ONE);
        when(jdbc.queryForMap(anyString(), any(Object[].class))).thenReturn(customer, list, productPrice);

        Map<String, Object> result = service.resolve(customerId, productId, explicitListId);

        assertThat(result).containsEntry("priceListId", explicitListId)
            .containsEntry("priceListCode", "LISTA_3");
    }

    @Test
    void rejectsInactiveExplicitListAsConflict() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID listId = UUID.randomUUID();
        Map<String, Object> customer = new HashMap<>();
        customer.put("price_list_id", null);
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
            .thenReturn(customer, Map.of("id", listId, "code", "LISTA_2", "status", "INACTIVE"));

        assertThatThrownBy(() -> service.resolve(customerId, productId, listId))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fallsBackToPreviousListWhenProductPriceIsMissing() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID listId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID previousListId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Map<String, Object> customer = new HashMap<>();
        customer.put("price_list_id", listId);
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
            .thenReturn(customer, Map.of("id", listId, "code", "LISTA_3", "status", "ACTIVE"));
        when(jdbc.queryForMap(org.mockito.ArgumentMatchers.contains("product_price_history"), any(Object[].class)))
            .thenThrow(new EmptyResultDataAccessException(1))
            .thenReturn(Map.of("priceListId", previousListId, "priceListCode", "LISTA_2",
                "productId", productId, "unitPrice", new BigDecimal("11.0000")));
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.contains("price_lists"), any(Object[].class)))
            .thenReturn(List.of(Map.of("id", previousListId, "code", "LISTA_2", "status", "ACTIVE")));

        Map<String, Object> result = service.resolve(customerId, productId, null);

        assertThat(result).containsEntry("priceListId", previousListId)
            .containsEntry("priceListCode", "LISTA_2")
            .containsEntry("unitPrice", new BigDecimal("11.0000"));
    }
}
