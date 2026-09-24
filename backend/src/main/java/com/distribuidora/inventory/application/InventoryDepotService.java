package com.distribuidora.inventory.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.shared.web.PageResponse;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InventoryDepotService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public InventoryDepotService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<Depot> list() {
        return jdbc.query("select id, code, name, status, is_default from inventory.depots order by is_default desc, code",
            (rs, row) -> new Depot(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("status"), rs.getBoolean("is_default")));
    }

    @Transactional
    public Depot create(String code, String name) {
        String normalizedCode = code == null ? null : code.trim().toUpperCase(java.util.Locale.ROOT);
        String normalizedName = name == null ? null : name.trim();
        if (normalizedCode == null || !normalizedCode.matches("[A-Z0-9_-]{2,40}")
            || normalizedName == null || normalizedName.isBlank() || normalizedName.length() > 120) {
            throw new IllegalArgumentException("code debe tener 2 a 40 caracteres alfanuméricos y name hasta 120 caracteres");
        }
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        try {
            jdbc.update("insert into inventory.depots(id, code, name, status, is_default, created_at, updated_at) values (?, ?, ?, 'ACTIVE', false, ?, ?)",
                id, normalizedCode, normalizedName, now, now);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new IllegalStateException("Ya existe un depósito con ese código", exception);
        }
        audit.recordWithinTransaction(actorId(), "DEPOT_CREATE", "DEPOT", id.toString(), "SUCCESS",
            Map.of("code", normalizedCode, "name", normalizedName));
        return new Depot(id, normalizedCode, normalizedName, "ACTIVE", false);
    }

    @Transactional
    public Depot setActive(UUID depotId, boolean active) {
        Map<String, Object> current;
        try {
            current = jdbc.queryForMap("select id, code, name, status, is_default from inventory.depots where id = ? for update", depotId);
        } catch (EmptyResultDataAccessException exception) {
            throw exception;
        }
        boolean isDefault = (Boolean) current.get("is_default");
        if (isDefault && !active) {
            throw new IllegalStateException("El depósito predeterminado no puede desactivarse");
        }
        String status = active ? "ACTIVE" : "INACTIVE";
        jdbc.update("update inventory.depots set status = ?, updated_at = ? where id = ?",
            status, Timestamp.from(Instant.now()), depotId);
        audit.recordWithinTransaction(actorId(), "DEPOT_STATUS", "DEPOT", depotId.toString(), "SUCCESS",
            Map.of("status", status));
        return new Depot(depotId, String.valueOf(current.get("code")), String.valueOf(current.get("name")), status, isDefault);
    }

    public PageResponse<Map<String, Object>> balances(UUID depotId, int page, int size, String search) {
        if (depotId == null || page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("depotId, page y size deben ser válidos (size máximo 100)");
        }
        jdbc.queryForObject("select id from inventory.depots where id = ?", UUID.class, depotId);
        String term = "%" + (search == null ? "" : search.trim().toLowerCase(java.util.Locale.ROOT)) + "%";
        long total = jdbc.queryForObject("select count(*) from catalog.products p where lower(p.name) like ? or lower(p.sku) like ?",
            Long.class, term, term);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select p.id as "productId", p.sku, p.name as product,
                   coalesce(b.quantity, 0) as stock, b.updated_at as updated
            from catalog.products p
            left join inventory.inventory_balances b on b.product_id = p.id and b.depot_id = ?
            where lower(p.name) like ? or lower(p.sku) like ?
            order by p.name, p.sku
            limit ? offset ?
            """, depotId, term, term, size, (long) page * size);
        return PageResponse.of(rows, page, size, total);
    }

    private UUID actorId() {
        try {
            return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
        } catch (Exception exception) {
            throw new IllegalArgumentException("El actor autenticado no tiene un UUID válido", exception);
        }
    }

    public record Depot(UUID id, String code, String name, String status, boolean isDefault) { }
}
