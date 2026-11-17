package com.distribuidora.inventory.application;

import com.distribuidora.audit.application.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Service
public class InventoryCommandService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final InventoryMovementService movements;

    public InventoryCommandService(JdbcTemplate jdbc, AuditService audit, InventoryMovementService movements) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.movements = movements;
    }

    /** Reason stored when the user leaves it empty; movements keep a non-empty reason. */
    public static final String DEFAULT_ADJUSTMENT_REASON = "Ajuste manual";
    public static final String DEFAULT_COUNT_REASON = "Ajuste a cantidad contada";

    @Transactional
    public void adjust(UUID productId, BigDecimal quantity, String reason) {
        validate(productId, quantity);
        String normalizedReason = normalizeReason(reason, DEFAULT_ADJUSTMENT_REASON);
        requireActiveProduct(productId);
        UUID actorId = actorId();
        movements.apply(productId, quantity, "MANUAL_ADJUSTMENT", null, normalizedReason);
        audit.recordWithinTransaction(actorId, "STOCK_ADJUSTMENT", "PRODUCT", productId.toString(), "SUCCESS",
            Map.of("quantity", quantity, "reason", normalizedReason));
    }

    /** Replaces the current stock with the counted quantity; the server computes the difference under lock. */
    @Transactional
    public void setQuantity(UUID productId, BigDecimal targetQuantity, String reason) {
        if (productId == null || targetQuantity == null || targetQuantity.remainder(HALF).signum() != 0) {
            throw new IllegalArgumentException("targetQuantity debe ser un múltiplo de 0.5");
        }
        String normalizedReason = normalizeReason(reason, DEFAULT_COUNT_REASON);
        requireActiveProduct(productId);
        UUID actorId = actorId();
        var result = movements.applyTarget(productId, targetQuantity, "MANUAL_ADJUSTMENT", normalizedReason);
        audit.recordWithinTransaction(actorId, "STOCK_ADJUSTMENT", "PRODUCT", productId.toString(), "SUCCESS",
            Map.of("quantity", result.delta(), "previousQuantity", result.previousQuantity(),
                "targetQuantity", targetQuantity, "reason", normalizedReason));
    }

    @Transactional
    public void recordShrinkage(UUID productId, UUID movementId, BigDecimal quantity, String reason) {
        if (quantity == null || quantity.signum() <= 0 || quantity.compareTo(InventoryMovementService.MAX_QUANTITY) > 0
            || quantity.remainder(HALF).signum() != 0) {
            throw new IllegalArgumentException("La cantidad de merma debe ser positiva y múltiplo de 0.5");
        }
        String description = reason == null ? "" : reason.strip();
        if (description.isBlank() || description.length() > 500) {
            throw new IllegalArgumentException("El motivo de la merma es obligatorio y admite hasta 500 caracteres");
        }
        UUID actor = actorId();
        if (movements.applyShrinkage(movementId, productId, quantity.negate(), description)) {
            audit.recordWithinTransaction(actor, "STOCK_SHRINKAGE", "STOCK_MOVEMENT", movementId.toString(), "SUCCESS",
                Map.of("productId", productId, "quantity", quantity.negate(), "reason", description));
        }
    }

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private void requireActiveProduct(UUID productId) {
        String status = jdbc.queryForObject(
            "select status from catalog.products where id = ?", String.class, productId);
        if (!"ACTIVE".equals(status)) {
            throw new IllegalStateException("El producto no está activo");
        }
    }

    private void validate(UUID productId, BigDecimal quantity) {
        if (productId == null || quantity == null || quantity.signum() == 0
            || quantity.remainder(HALF).signum() != 0) {
            throw new IllegalArgumentException("quantity debe ser un múltiplo distinto de cero de 0.5");
        }
    }

    private String normalizeReason(String reason, String fallback) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.length() > 500) {
            throw new IllegalArgumentException("reason admite hasta 500 caracteres");
        }
        return normalized.isEmpty() ? fallback : normalized;
    }

    private UUID actorId() {
        try {
            return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
        } catch (Exception exception) {
            throw new IllegalArgumentException("El actor autenticado no tiene un UUID válido", exception);
        }
    }

}
