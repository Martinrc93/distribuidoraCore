package com.distribuidora.inventory.application;

import com.distribuidora.audit.application.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
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

    @Transactional
    public void adjust(UUID productId, BigDecimal quantity, String reason) {
        adjust(InventoryMovementService.DEFAULT_DEPOT_ID, productId, quantity, reason);
    }

    @Transactional
    public void adjust(UUID depotId, UUID productId, BigDecimal quantity, String reason) {
        validate(productId, quantity, reason);
        UUID resolvedDepotId = depotId == null ? InventoryMovementService.DEFAULT_DEPOT_ID : depotId;
        String status = jdbc.queryForObject(
            "select status from catalog.products where id = ?", String.class, productId);
        if (!"ACTIVE".equals(status)) {
            throw new IllegalStateException("El producto no está activo");
        }
        UUID actorId = actorId();
        movements.apply(resolvedDepotId, productId, quantity, "MANUAL_ADJUSTMENT", null, reason);
        audit.recordWithinTransaction(actorId, "STOCK_ADJUSTMENT", "PRODUCT", productId.toString(), "SUCCESS",
            Map.of("depotId", resolvedDepotId.toString(), "quantity", quantity, "reason", reason.trim()));
    }

    @Transactional
    public UUID transfer(UUID fromDepotId, UUID toDepotId, UUID productId, BigDecimal quantity, String reason) {
        UUID actorId = actorId();
        UUID transferId = movements.transfer(fromDepotId, toDepotId, productId, quantity, reason);
        audit.recordWithinTransaction(actorId, "STOCK_TRANSFER", "STOCK_TRANSFER", transferId.toString(), "SUCCESS",
            Map.of("fromDepotId", fromDepotId.toString(), "toDepotId", toDepotId.toString(),
                "productId", productId.toString(), "quantity", quantity, "reason", reason.trim()));
        return transferId;
    }

    private void validate(UUID productId, BigDecimal quantity, String reason) {
        if (productId == null || quantity == null || quantity.signum() == 0
            || quantity.remainder(new BigDecimal("0.5")).signum() != 0
            || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("quantity debe ser un múltiplo distinto de cero de 0.5 y reason es obligatorio (máximo 500 caracteres)");
        }
    }

    private UUID actorId() {
        try {
            return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
        } catch (Exception exception) {
            throw new IllegalArgumentException("El actor autenticado no tiene un UUID válido", exception);
        }
    }

}
