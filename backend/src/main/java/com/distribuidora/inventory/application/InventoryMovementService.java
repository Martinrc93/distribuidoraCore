package com.distribuidora.inventory.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class InventoryMovementService {
    public static final UUID DEFAULT_DEPOT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final BigDecimal HALF = new BigDecimal("0.5");
    private static final Set<String> SUPPORTED_MOVEMENT_TYPES = Set.of(
        "SALE", "SALE_CANCELLATION", "MANUAL_ADJUSTMENT", "RETURN", "TRANSFER_OUT", "TRANSFER_IN");

    private final JdbcTemplate jdbc;

    public InventoryMovementService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void apply(UUID productId, BigDecimal delta, String movementType, UUID referenceId, String reason) {
        apply(DEFAULT_DEPOT_ID, productId, delta, movementType, referenceId, reason);
    }

    @Transactional
    public void apply(UUID depotId, UUID productId, BigDecimal delta, String movementType, UUID referenceId,
                      String reason) {
        UUID resolvedDepotId = depotId == null ? DEFAULT_DEPOT_ID : depotId;
        validate(productId, delta, movementType, reason);
        requireActiveProductForNewMovement(productId, movementType);
        requireDepot(resolvedDepotId, isHistoricalReversal(movementType));

        Timestamp now = Timestamp.from(Instant.now());
        if (!isHistoricalReversal(movementType)) {
            ensureBalance(resolvedDepotId, productId, now);
        }
        BigDecimal current = jdbc.queryForObject(
            "select quantity from inventory.inventory_balances where depot_id = ? and product_id = ? for update",
            BigDecimal.class, resolvedDepotId, productId);
        BigDecimal updated = current.add(delta);
        jdbc.update(
            "update inventory.inventory_balances set quantity = ?, updated_at = ? where depot_id = ? and product_id = ?",
            updated, now, resolvedDepotId, productId);
        insertMovement(resolvedDepotId, productId, delta, movementType, referenceId, reason, now);
    }

    @Transactional
    public UUID transfer(UUID fromDepotId, UUID toDepotId, UUID productId, BigDecimal quantity, String reason) {
        if (fromDepotId == null || toDepotId == null || productId == null || fromDepotId.equals(toDepotId)) {
            throw new IllegalArgumentException("Los depósitos de origen y destino deben ser distintos y obligatorios");
        }
        if (quantity == null || quantity.signum() <= 0 || quantity.remainder(HALF).signum() != 0
            || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("quantity debe ser positiva, múltiplo de 0.5 y reason es obligatorio (máximo 500 caracteres)");
        }
        requireActiveProductForNewMovement(productId, "TRANSFER_OUT");
        requireDepot(fromDepotId, false);
        requireDepot(toDepotId, false);

        Timestamp now = Timestamp.from(Instant.now());
        List<UUID> depotIds = List.of(fromDepotId, toDepotId).stream()
            .sorted(Comparator.comparing(UUID::toString)).toList();
        depotIds.forEach(id -> ensureBalance(id, productId, now));
        List<Map<String, Object>> balances = jdbc.queryForList(
            "select depot_id, quantity from inventory.inventory_balances "
                + "where product_id = ? and depot_id in (?, ?) order by depot_id for update",
            productId, depotIds.get(0), depotIds.get(1));
        BigDecimal sourceQuantity = balances.stream()
            .filter(row -> fromDepotId.equals(uuid(row.get("depot_id"))))
            .map(row -> (BigDecimal) row.get("quantity")).findFirst()
            .orElseThrow(() -> new IllegalStateException("No se encontró el saldo del depósito de origen"));
        if (sourceQuantity.compareTo(quantity) < 0) {
            throw new IllegalStateException("El depósito de origen no tiene stock suficiente para transferir");
        }

        UUID transferId = UUID.randomUUID();
        apply(fromDepotId, productId, quantity.negate(), "TRANSFER_OUT", transferId, reason);
        apply(toDepotId, productId, quantity, "TRANSFER_IN", transferId, reason);
        return transferId;
    }

    private void ensureBalance(UUID depotId, UUID productId, Timestamp now) {
        jdbc.update("insert into inventory.inventory_balances(depot_id, product_id, quantity, updated_at) "
                + "values (?, ?, 0, ?) on conflict (depot_id, product_id) do nothing",
            depotId, productId, now);
    }

    private void insertMovement(UUID depotId, UUID productId, BigDecimal delta, String movementType,
                                UUID referenceId, String reason, Timestamp now) {
        jdbc.update(
            "insert into inventory.stock_movements(id, depot_id, product_id, movement_type, quantity, reason, reference_type, reference_id, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            UUID.randomUUID(), depotId, productId, movementType, delta, reason.trim(),
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

    private void requireDepot(UUID depotId, boolean allowInactive) {
        String status = jdbc.queryForObject(
            "select status from inventory.depots where id = ?", String.class, depotId);
        if (!allowInactive && !"ACTIVE".equals(status)) {
            throw new IllegalStateException("El depósito no está activo");
        }
    }

    private boolean isHistoricalReversal(String movementType) {
        return "SALE_CANCELLATION".equals(movementType) || "RETURN".equals(movementType);
    }

    private UUID uuid(Object value) {
        return value instanceof UUID id ? id : UUID.fromString(String.valueOf(value));
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
