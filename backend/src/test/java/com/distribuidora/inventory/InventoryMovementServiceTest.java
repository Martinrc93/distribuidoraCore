package com.distribuidora.inventory;

import com.distribuidora.inventory.application.InventoryMovementService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class InventoryMovementServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final InventoryMovementService service = new InventoryMovementService(jdbc);

    @Test
    void appliesSaleWithLockedBalanceAndReference() {
        UUID productId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        when(jdbc.queryForObject(eq("select status from catalog.products where id = ?"), eq(String.class), eq(productId)))
            .thenReturn("ACTIVE");
        when(jdbc.queryForObject(
            eq("select quantity from inventory.inventory_balances where product_id = ? for update"),
            eq(BigDecimal.class), eq(productId))).thenReturn(new BigDecimal("1.0"));

        service.apply(productId, new BigDecimal("-1.5"), "SALE", saleId, "Venta confirmada");

        verify(jdbc).update(
            eq("update inventory.inventory_balances set quantity = ?, updated_at = ? where product_id = ?"),
            eq(new BigDecimal("-0.5")), any(), eq(productId));
        verify(jdbc).update(
            eq("insert into inventory.stock_movements(id, product_id, movement_type, quantity, reason, reference_type, reference_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)"),
            any(UUID.class), eq(productId), eq("SALE"), eq(new BigDecimal("-1.5")), eq("Venta confirmada"),
            eq("SALE"), eq(saleId), any());
    }

    @Test
    void permitsSaleCancellationAndNegativeBalances() {
        UUID productId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(productId))).thenReturn("ACTIVE");
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), eq(productId))).thenReturn(new BigDecimal("-2.0"));

        service.apply(productId, new BigDecimal("0.5"), "SALE_CANCELLATION", UUID.randomUUID(), "Cancelación");

        verify(jdbc).update(
            eq("update inventory.inventory_balances set quantity = ?, updated_at = ? where product_id = ?"),
            eq(new BigDecimal("-1.5")), any(), eq(productId));
    }

    @Test
    void permitsHistoricalSaleCancellationForInactiveProductButRejectsNewMovements() {
        UUID productId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(productId))).thenReturn("INACTIVE");
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), eq(productId))).thenReturn(new BigDecimal("-2.0"));

        service.apply(productId, new BigDecimal("0.5"), "SALE_CANCELLATION", UUID.randomUUID(), "Cancelación histórica");

        assertThatThrownBy(() -> service.apply(productId, new BigDecimal("-0.5"), "SALE", UUID.randomUUID(), "Venta nueva"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.apply(productId, new BigDecimal("0.5"), "MANUAL_ADJUSTMENT", null, "Ajuste actual"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnsupportedTypesAndInvalidDeltasBeforeDatabaseAccess() {
        UUID productId = UUID.randomUUID();

        assertThatThrownBy(() -> service.apply(productId, BigDecimal.ONE, "UNKNOWN", UUID.randomUUID(), "Devolución"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.apply(productId, new BigDecimal("0.25"), "SALE", UUID.randomUUID(), "Venta"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.apply(productId, BigDecimal.ZERO, "SALE", UUID.randomUUID(), "Venta"))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void appliesReturnToInactiveProductAndRecordsPositiveMovement() {
        UUID productId = UUID.randomUUID();
        UUID returnId = UUID.randomUUID();
        when(jdbc.queryForObject(
            eq("select quantity from inventory.inventory_balances where product_id = ? for update"),
            eq(BigDecimal.class), eq(productId))).thenReturn(new BigDecimal("-1.0"));

        service.apply(productId, new BigDecimal("0.5"), "RETURN", returnId, "Producto devuelto");

        verify(jdbc).update(
            eq("update inventory.inventory_balances set quantity = ?, updated_at = ? where product_id = ?"),
            eq(new BigDecimal("-0.5")), any(), eq(productId));
        verify(jdbc).update(
            eq("insert into inventory.stock_movements(id, product_id, movement_type, quantity, reason, reference_type, reference_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)"),
            any(UUID.class), eq(productId), eq("RETURN"), eq(new BigDecimal("0.5")), eq("Producto devuelto"),
            eq("RETURN"), eq(returnId), any());
    }
}
