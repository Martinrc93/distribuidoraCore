package com.distribuidora.inventory.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
public class InventoryMovementService {
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final Set<String> SUPPORTED_MOVEMENT_TYPES = Set.of(
        "SALE", "SALE_CANCELLATION", "MANUAL_ADJUSTMENT", "RETURN");

    private final JdbcTemplate jdbc;

    public InventoryMovementService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void apply(UUID productId, BigDecimal delta, String movementType, UUID referenceId, String reason) {
        validate(productId, delta, movementType, reason);
        requireActiveProductForNewMovement(productId, movementType);

        Timestamp now = Timestamp.from(Instant.now());
        if (!isHistoricalReversal(movementType)) {
            ensureBalance(productId, now);
        }
        BigDecimal current = jdbc.queryForObject(
            "select quantity from inventory.inventory_balances where product_id = ? for update",
            BigDecimal.class, productId);
        BigDecimal updated = current.add(delta);
        jdbc.update(
            "update inventory.inventory_balances set quantity = ?, updated_at = ? where product_id = ?",
            updated, now, productId);
        insertMovement(productId, delta, movementType, referenceId, reason, now);
    }

    private void ensureBalance(UUID productId, Timestamp now) {
        jdbc.update("insert into inventory.inventory_balances(product_id, quantity, updated_at) "
                + "values (?, 0, ?) on conflict (product_id) do nothing",
            productId, now);
    }

    private void insertMovement(UUID productId, BigDecimal delta, String movementType,
                                UUID referenceId, String reason, Timestamp now) {
        jdbc.update(
            "insert into inventory.stock_movements(id, product_id, movement_type, quantity, reason, reference_type, reference_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), productId, movementType, delta, reason.trim(),
            referenceId == null ? null : movementType, referenceId, now);
    }

    private void requireActiveProductForNewMovement(UUID productId, String movementType) {
        if (!isHistoricalReversal(movementType)) {
            String status = jdbc.queryForObject(
                "select status from catalog.products where id = ?", String.class, productId);
            if (!"ACTIVE".equals(status)) {
                throw new IllegalStateException("El producto no está activo");
            }
        }
    }

    private boolean isHistoricalReversal(String movementType) {
        return "SALE_CANCELLATION".equals(movementType) || "RETURN".equals(movementType);
    }

    private void validate(UUID productId, BigDecimal delta, String movementType, String reason) {
        if (productId == null || delta == null || delta.signum() == 0
            || delta.remainder(HALF).signum() != 0
            || movementType == null || !SUPPORTED_MOVEMENT_TYPES.contains(movementType)
            || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("delta debe ser un múltiplo distinto de cero de 0.5, movementType no soportado y reason es obligatorio (máximo 500 caracteres)");
        }
    }
}
