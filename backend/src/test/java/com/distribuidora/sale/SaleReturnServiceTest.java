package com.distribuidora.sale;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryMovementService;
import com.distribuidora.sale.api.SaleReturnDtos;
import com.distribuidora.sale.application.SaleReturnService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SaleReturnServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final InventoryMovementService inventory = mock(InventoryMovementService.class);
    private final AuditService audit = mock(AuditService.class);
    private final SaleReturnService service = new SaleReturnService(jdbc, inventory, audit);

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsPartialReturnAndRestocksEachLine() {
        UUID actorId = authenticate();
        UUID saleId = UUID.randomUUID();
        UUID saleItemId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(jdbc.queryForMap(anyString(), eq(saleId))).thenReturn(Map.of("sale_status", "DELIVERED", "order_status", "DELIVERED"));
        when(jdbc.queryForList(anyString(), eq(saleItemId), eq(saleId))).thenReturn(List.of(
            Map.of("id", saleItemId, "product_id", productId, "quantity", new BigDecimal("2.0000"))));
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), eq(saleId), eq(saleItemId))).thenReturn(new BigDecimal("0.5000"));

        SaleReturnService.ReturnResult response = service.create(saleId,
            new SaleReturnDtos.ReturnRequest("Envase dañado", List.of(new SaleReturnDtos.ReturnItemRequest(saleItemId, new BigDecimal("0.5")))));

        assertThat(response.saleId()).isEqualTo(saleId);
        assertThat(response.reason()).isEqualTo("Envase dañado");
        assertThat(response.items()).containsExactly(new SaleReturnService.ReturnItemResult(saleItemId, productId, new BigDecimal("0.5000")));
        verify(inventory).apply(eq(productId), eq(new BigDecimal("0.5000")),
            eq("RETURN"), eq(response.returnId()), eq("Envase dañado"));
        verify(audit).recordWithinTransaction(eq(actorId), eq("SALE_RETURN"), eq("SALE"), eq(saleId.toString()), eq("SUCCESS"), any());
    }

    @Test
    void rejectsReturnWhenSaleIsNotDelivered() {
        authenticate();
        UUID saleId = UUID.randomUUID();
        when(jdbc.queryForMap(anyString(), eq(saleId))).thenReturn(Map.of("sale_status", "CONFIRMED", "order_status", "CONFIRMED"));

        assertThatThrownBy(() -> service.create(saleId, validRequest()))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(inventory, audit);
    }

    @Test
    void rejectsReturnBeyondRemainingQuantity() {
        authenticate();
        UUID saleId = UUID.randomUUID();
        UUID saleItemId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(jdbc.queryForMap(anyString(), eq(saleId))).thenReturn(Map.of("sale_status", "DELIVERED", "order_status", "DELIVERED"));
        when(jdbc.queryForList(anyString(), eq(saleItemId), eq(saleId))).thenReturn(List.of(
            Map.of("id", saleItemId, "product_id", productId, "quantity", new BigDecimal("1.0000"))));
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), eq(saleId), eq(saleItemId))).thenReturn(new BigDecimal("0.5000"));

        assertThatThrownBy(() -> service.create(saleId, new SaleReturnDtos.ReturnRequest("Daño",
            List.of(new SaleReturnDtos.ReturnItemRequest(saleItemId, BigDecimal.ONE)))))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(inventory, audit);
    }

    @Test
    void rejectsDuplicateLinesAndNonHalfUnitQuantitiesBeforeDatabaseAccess() {
        authenticate();
        UUID saleItemId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        var duplicate = new SaleReturnDtos.ReturnRequest("Daño", List.of(
            new SaleReturnDtos.ReturnItemRequest(saleItemId, new BigDecimal("0.5")),
            new SaleReturnDtos.ReturnItemRequest(saleItemId, new BigDecimal("0.5"))));
        var fraction = new SaleReturnDtos.ReturnRequest("Daño",
            List.of(new SaleReturnDtos.ReturnItemRequest(saleItemId, new BigDecimal("0.25"))));
        when(jdbc.queryForMap(anyString(), eq(saleId))).thenReturn(Map.of("sale_status", "DELIVERED", "order_status", "DELIVERED"));

        assertThatThrownBy(() -> service.create(saleId, duplicate)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(saleId, fraction)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(inventory, audit);
    }

    private UUID authenticate() {
        UUID actorId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(actorId.toString(), "test"));
        return actorId;
    }

    private SaleReturnDtos.ReturnRequest validRequest() {
        return new SaleReturnDtos.ReturnRequest("Daño",
            List.of(new SaleReturnDtos.ReturnItemRequest(UUID.randomUUID(), new BigDecimal("0.5"))));
    }
}
