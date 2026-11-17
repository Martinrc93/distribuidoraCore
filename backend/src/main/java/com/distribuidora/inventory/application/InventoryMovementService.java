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
    static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999999999.9999");
    private static final String SHRINKAGE = "SHRINKAGE";
    private static final String INSERT_MOVEMENT =
        "insert into inventory.stock_movements(id, product_id, movement_type, quantity, reason, reference_type, reference_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final Set<String> SUPPORTED_MOVEMENT_TYPES = Set.of(
        "SALE", "SALE_CANCELLATION", "MANUAL_ADJUSTMENT", "RETURN",
        "PURCHASE", "PURCHASE_ADJUSTMENT", "PURCHASE_CANCELLATION");

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
        updateBalance(productId, delta, now);
        insertMovement(productId, delta, movementType, referenceId, reason, now);
    }

    /** Returns false on a replay of an already-applied movement with the same identifier and data. */
    @Transactional
    public boolean applyShrinkage(UUID movementId, UUID productId, BigDecimal delta, String reason) {
        if (movementId == null || delta == null || delta.signum() >= 0 || delta.abs().compareTo(MAX_QUANTITY) > 0) {
            throw new IllegalArgumentException("La merma requiere un identificador y una cantidad válida para descontar");
        }
        validateMovement(productId, delta, reason);
        if (isShrinkageReplay(movementId, productId, delta, reason)) return false;

        // Hold the product active until the movement and its stock change are committed.
        String status = jdbc.queryForObject(
            "select status from catalog.products where id = ? for share", String.class, productId);
        if (!"ACTIVE".equals(status)) {
            if (isShrinkageReplay(movementId, productId, delta, reason)) return false;
            throw new IllegalStateException("El producto no está activo");
        }
        Timestamp now = Timestamp.from(Instant.now());
        int inserted = jdbc.update(INSERT_MOVEMENT + " on conflict (id) do nothing",
            movementId, productId, SHRINKAGE, delta, reason, null, null, now);
        if (inserted == 0) {
            if (isShrinkageReplay(movementId, productId, delta, reason)) return false;
            throw new IllegalStateException("Ya existe un movimiento con ese identificador");
        }
        ensureBalance(productId, now);
        updateBalance(productId, delta, now);
        return true;
    }

    private boolean isShrinkageReplay(UUID movementId, UUID productId, BigDecimal delta, String reason) {
        var existing = jdbc.queryForList("""
            select product_id, movement_type, quantity, reason from inventory.stock_movements where id = ?
            """, movementId);
        if (existing.isEmpty()) return false;
        var movement = existing.getFirst();
        if (!SHRINKAGE.equals(movement.get("movement_type")) || !productId.equals(movement.get("product_id"))
            || delta.compareTo((BigDecimal) movement.get("quantity")) != 0 || !reason.equals(movement.get("reason"))) {
            throw new IllegalStateException("La clave de la merma ya fue usada con otros datos");
        }
        return true;
    }

    private void updateBalance(UUID productId, BigDecimal delta, Timestamp now) {
        BigDecimal current = jdbc.queryForObject(
            "select quantity from inventory.inventory_balances where product_id = ? for update",
            BigDecimal.class, productId);
        BigDecimal updated = current.add(delta);
        if (updated.abs().compareTo(MAX_QUANTITY) > 0) {
            throw new IllegalArgumentException("El saldo resultante supera el rango admitido");
        }
        jdbc.update(
            "update inventory.inventory_balances set quantity = ?, updated_at = ? where product_id = ?",
            updated, now, productId);
    }

    public record TargetResult(BigDecimal previousQuantity, BigDecimal delta) { }

    /**
     * Sets the balance to {@code targetQuantity} under the same row lock as {@link #apply}; the difference is recorded
     * as an append-only movement, so the history keeps explaining every change.
     */
    @Transactional
    public TargetResult applyTarget(UUID productId, BigDecimal targetQuantity, String movementType, String reason) {
        if (productId == null || targetQuantity == null || targetQuantity.remainder(HALF).signum() != 0) {
            throw new IllegalArgumentException("La cantidad final debe ser un múltiplo de 0.5");
        }
        requireActiveProductForNewMovement(productId, movementType);
        Timestamp now = Timestamp.from(Instant.now());
        ensureBalance(productId, now);
        BigDecimal current = jdbc.queryForObject(
            "select quantity from inventory.inventory_balances where product_id = ? for update",
            BigDecimal.class, productId);
        BigDecimal delta = targetQuantity.subtract(current);
        if (delta.signum() == 0) {
            throw new IllegalStateException("El stock ya tiene esa cantidad");
        }
        validate(productId, delta, movementType, reason);
        jdbc.update(
            "update inventory.inventory_balances set quantity = ?, updated_at = ? where product_id = ?",
            targetQuantity, now, productId);
        insertMovement(productId, delta, movementType, null, reason, now);
        return new TargetResult(current, delta);
    }

    private void ensureBalance(UUID productId, Timestamp now) {
        jdbc.update("insert into inventory.inventory_balances(product_id, quantity, updated_at) "
                + "values (?, 0, ?) on conflict (product_id) do nothing",
            productId, now);
    }

    private void insertMovement(UUID productId, BigDecimal delta, String movementType,
                                UUID referenceId, String reason, Timestamp now) {
        jdbc.update(
            INSERT_MOVEMENT,
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
        // Reversals must work even if the product was deactivated after the original movement.
        return "SALE_CANCELLATION".equals(movementType) || "RETURN".equals(movementType)
            || "PURCHASE_CANCELLATION".equals(movementType);
    }

    private void validate(UUID productId, BigDecimal delta, String movementType, String reason) {
        if (movementType == null || !SUPPORTED_MOVEMENT_TYPES.contains(movementType)) {
            throw new IllegalArgumentException("movementType no soportado");
        }
        validateMovement(productId, delta, reason);
    }

    private void validateMovement(UUID productId, BigDecimal delta, String reason) {
        if (productId == null || delta == null || delta.signum() == 0
            || delta.remainder(HALF).signum() != 0
            || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("La cantidad debe ser un múltiplo distinto de cero de 0.5 y el motivo es obligatorio (máximo 500 caracteres)");
        }
    }
}
