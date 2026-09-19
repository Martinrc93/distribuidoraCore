package com.distribuidora.pricing;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.pricing.application.PricingCommandService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class PricingCommandServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final PricingCommandService service = new PricingCommandService(jdbc, audit);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsListWhenTotalIsBelowTenAndAuditsCreation() {
        UUID actorId = authenticate();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("CUSTOM"))).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(3);

        UUID id = service.createList(" CUSTOM ", " Clientes ");

        assertThat(id).isNotNull();
        verify(jdbc).update(anyString(), eq(id), eq("CUSTOM"), eq("Clientes"), any(), any());
        verify(audit).record(eq(actorId), eq("PRICELIST_CREATE"), eq("PRICE_LIST"), eq(id.toString()),
            eq("SUCCESS"), eq(Map.of("code", "CUSTOM")));
    }

    @Test
    void locksListCreationBeforeCheckingCodeAndCount() {
        authenticate();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("CUSTOM"))).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(3);

        service.createList("CUSTOM", "Clientes");

        var order = inOrder(jdbc);
        order.verify(jdbc).queryForObject(eq("select pg_advisory_xact_lock(?)"), eq(Object.class), eq(4_839_271_106L));
        order.verify(jdbc).queryForObject(anyString(), eq(Boolean.class), eq("CUSTOM"));
        order.verify(jdbc).queryForObject(anyString(), eq(Integer.class));
        order.verify(jdbc).update(anyString(), any(), eq("CUSTOM"), eq("Clientes"), any(), any());
    }

    @Test
    void rejectsDuplicateCodeAndMaximumListCount() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("CUSTOM"))).thenReturn(true);
        assertThatThrownBy(() -> service.createList("CUSTOM", "Clientes"))
            .isInstanceOf(IllegalStateException.class);

        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("L11"))).thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(10);
        assertThatThrownBy(() -> service.createList("L11", "Once"))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void renamesExistingListAndAuditsUpdate() {
        UUID listId = UUID.randomUUID();
        UUID actorId = authenticate();
        when(jdbc.update(anyString(), eq("Clientes"), any(), eq(listId))).thenReturn(1);

        service.renameList(listId, " Clientes ");

        verify(audit).record(eq(actorId), eq("PRICELIST_UPDATE"), eq("PRICE_LIST"), eq(listId.toString()),
            eq("SUCCESS"), eq(Map.of("name", "Clientes")));
    }

    @Test
    void rejectsRenamingMissingList() {
        UUID listId = UUID.randomUUID();
        when(jdbc.update(anyString(), eq("Clientes"), eq(listId))).thenReturn(0);

        assertThatThrownBy(() -> service.renameList(listId, "Clientes"))
            .isInstanceOf(EmptyResultDataAccessException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsDeactivationOfDefaultAndActivatesInactiveList() {
        UUID generalId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(generalId))).thenReturn("ACTIVE");
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(generalId))).thenReturn(true);
        assertThatThrownBy(() -> service.setListStatus(generalId, "INACTIVE"))
            .isInstanceOf(IllegalStateException.class);

        UUID inactiveId = UUID.randomUUID();
        UUID actorId = authenticate();
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(inactiveId))).thenReturn("INACTIVE");
        when(jdbc.update(anyString(), eq("ACTIVE"), eq(inactiveId))).thenReturn(1);
        service.setListStatus(inactiveId, "ACTIVE");

        verify(audit).record(eq(actorId), eq("PRICELIST_STATUS"), eq("PRICE_LIST"), eq(inactiveId.toString()),
            eq("SUCCESS"), eq(Map.of("status", "ACTIVE")));
    }

    @Test
    void upsertsPriceForActiveListAndProductAndAuditsUpdate() {
        UUID listId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID actorId = authenticate();
        BigDecimal price = new BigDecimal("10.2500");
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(listId))).thenReturn("ACTIVE");
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(productId))).thenReturn("ACTIVE");

        service.setProductPrice(listId, productId, price);

        verify(jdbc).update(
            eq("insert into catalog.product_prices(price_list_id, product_id, price, created_at, updated_at) values (?, ?, ?, ?, ?) on conflict (price_list_id, product_id) do update set price = excluded.price, updated_at = excluded.updated_at"),
            eq(listId), eq(productId), eq(price), any(), any());
        verify(audit).record(eq(actorId), eq("PRODUCT_PRICE_UPDATE"), eq("PRODUCT_PRICE"),
            eq(listId + ":" + productId), eq("SUCCESS"), eq(Map.of("price", price)));
    }

    @Test
    void rejectsNegativePriceAndInactiveListOrProduct() {
        UUID listId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        assertThatThrownBy(() -> service.setProductPrice(listId, productId, new BigDecimal("-1")))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc, audit);

        when(jdbc.queryForObject(anyString(), eq(String.class), eq(listId))).thenReturn("INACTIVE");
        assertThatThrownBy(() -> service.setProductPrice(listId, productId, BigDecimal.ONE))
            .isInstanceOf(IllegalStateException.class);

        when(jdbc.queryForObject(anyString(), eq(String.class), eq(listId))).thenReturn("ACTIVE");
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(productId))).thenReturn("INACTIVE");
        assertThatThrownBy(() -> service.setProductPrice(listId, productId, BigDecimal.ONE))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsPricesOutsideNumericPrecisionScaleAndIntegerDigitBounds() {
        UUID listId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        authenticate();

        assertThatThrownBy(() -> service.setProductPrice(listId, productId, new BigDecimal("1000000000000000")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setProductPrice(listId, productId, new BigDecimal("1.00000")))
            .isInstanceOf(IllegalArgumentException.class);

        when(jdbc.queryForObject(anyString(), eq(String.class), eq(listId))).thenReturn("ACTIVE");
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(productId))).thenReturn("ACTIVE");
        service.setProductPrice(listId, productId, new BigDecimal("999999999999999.9999"));

        verify(jdbc).update(
            eq("insert into catalog.product_prices(price_list_id, product_id, price, created_at, updated_at) values (?, ?, ?, ?, ?) on conflict (price_list_id, product_id) do update set price = excluded.price, updated_at = excluded.updated_at"),
            eq(listId), eq(productId), eq(new BigDecimal("999999999999999.9999")), any(), any());
    }

    private UUID authenticate() {
        UUID actorId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actorId.toString(), null)
        );
        return actorId;
    }
}
