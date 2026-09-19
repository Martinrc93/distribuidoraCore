package com.distribuidora.inventory;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.inventory.application.InventoryCommandService;
import com.distribuidora.inventory.application.InventoryMovementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class InventoryCommandServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AuditService audit = mock(AuditService.class);
    private final InventoryMovementService movements = new InventoryMovementService(jdbc);
    private final InventoryCommandService service = new InventoryCommandService(jdbc, audit, movements);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsInvalidQuantities() {
        UUID productId = UUID.randomUUID();

        for (BigDecimal quantity : new BigDecimal[]{
            new BigDecimal("0"), new BigDecimal("0.25"), new BigDecimal("-0.25")
        }) {
            assertThatThrownBy(() -> service.adjust(productId, quantity, "Corrección"))
                .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(jdbc, audit);
    }

    @Test
    void acceptsSignedHalfUnitQuantities() {
        UUID productId = UUID.randomUUID();
        authenticate();
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(productId))).thenReturn("ACTIVE");
        when(jdbc.queryForObject(anyString(), eq(BigDecimal.class), eq(productId))).thenReturn(BigDecimal.TEN);

        service.adjust(productId, new BigDecimal("-1.5"), "Corrección");
        service.adjust(productId, new BigDecimal("2.0"), "Reposición");

        verify(jdbc, org.mockito.Mockito.times(4)).queryForObject(anyString(), eq(String.class), eq(productId));
    }

    @Test
    void rejectsMissingOrTooLongReason() {
        UUID productId = UUID.randomUUID();
        String tooLong = "a".repeat(501);

        assertThatThrownBy(() -> service.adjust(productId, BigDecimal.ONE, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.adjust(productId, BigDecimal.ONE, "  "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.adjust(productId, BigDecimal.ONE, tooLong))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc, audit);
    }

    @Test
    void locksBalanceUpdatesItRecordsMovementAndAuditsAdjustment() {
        UUID productId = UUID.randomUUID();
        UUID actorId = authenticate();
        when(jdbc.queryForObject(
            eq("select status from catalog.products where id = ?"), eq(String.class), eq(productId)))
            .thenReturn("ACTIVE");
        when(jdbc.queryForObject(
            eq("select quantity from inventory.inventory_balances where product_id = ? for update"),
            eq(BigDecimal.class), eq(productId)))
            .thenReturn(new BigDecimal("10.0"));

        BigDecimal delta = new BigDecimal("-1.5");
        service.adjust(productId, delta, "Merma");

        verify(jdbc).update(
            eq("update inventory.inventory_balances set quantity = ?, updated_at = ? where product_id = ?"),
            eq(new BigDecimal("8.5")), any(), eq(productId));
        verify(jdbc).update(
            eq("insert into inventory.stock_movements(id, product_id, movement_type, quantity, reason, reference_type, reference_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)"),
            any(UUID.class), eq(productId), eq("MANUAL_ADJUSTMENT"), eq(delta), eq("Merma"),
            eq(null), eq(null), any());
        verify(audit).record(eq(actorId), eq("STOCK_ADJUSTMENT"), eq("PRODUCT"), eq(productId.toString()),
            eq("SUCCESS"), eq(Map.of("quantity", delta, "reason", "Merma")));
    }

    @Test
    void rejectsInactiveProductBeforeLockingBalance() {
        UUID productId = UUID.randomUUID();
        when(jdbc.queryForObject(
            eq("select status from catalog.products where id = ?"), eq(String.class), eq(productId)))
            .thenReturn("INACTIVE");

        assertThatThrownBy(() -> service.adjust(productId, BigDecimal.ONE, "Corrección"))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(audit);
        verify(jdbc, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsInvalidAuthenticatedActorIdBeforeChangingStock() {
        UUID productId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("not-a-uuid", null)
        );
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(productId))).thenReturn("ACTIVE");

        assertThatThrownBy(() -> service.adjust(productId, BigDecimal.ONE, "Corrección"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("El actor autenticado no tiene un UUID válido");
        verify(jdbc).queryForObject(anyString(), eq(String.class), eq(productId));
        verifyNoInteractions(audit);
    }

    private UUID authenticate() {
        UUID actorId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actorId.toString(), null)
        );
        return actorId;
    }
}
