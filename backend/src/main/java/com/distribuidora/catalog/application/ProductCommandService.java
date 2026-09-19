package com.distribuidora.catalog.application;

import com.distribuidora.audit.application.AuditService;
import org.springframework.dao.EmptyResultDataAccessException;
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
public class ProductCommandService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ProductCommandService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record ProductInput(String sku, String name, String category, String presentation, BigDecimal cost, BigDecimal price) { }

    public static void validate(ProductInput input) {
        if (input == null || blank(input.sku()) || blank(input.name()) || blank(input.category()) || blank(input.presentation())) {
            throw new IllegalArgumentException("sku, name, category y presentation son obligatorios");
        }
        if (input.cost() == null || input.price() == null || input.cost().signum() < 0 || input.price().signum() < 0) {
            throw new IllegalArgumentException("cost y price no pueden ser negativos");
        }
    }

    @Transactional
    public UUID create(ProductInput input) {
        validate(input);
        if (exists("select exists(select 1 from catalog.products where sku = ?)", input.sku())) {
            throw new IllegalStateException("Ya existe un producto con ese SKU");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into catalog.products(id, sku, name, category, presentation, cost, price, status, created_at)
            values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
            """, id, input.sku().trim(), input.name().trim(), input.category().trim(), input.presentation().trim(), input.cost(), input.price(), timestamp());
        jdbc.update("insert into inventory.inventory_balances(product_id, quantity, updated_at) values (?, 0, ?)", id, timestamp());
        audit.record(actorId(), "PRODUCT_CREATE", "PRODUCT", id.toString(), "SUCCESS", Map.of("sku", input.sku()));
        return id;
    }

    @Transactional
    public void update(UUID id, ProductInput input) {
        validate(input);
        if (!exists("select exists(select 1 from catalog.products where id = ?)", id)) throw new EmptyResultDataAccessException(1);
        if (exists("select exists(select 1 from catalog.products where sku = ? and id <> ?)", input.sku(), id)) {
            throw new IllegalStateException("Ya existe un producto con ese SKU");
        }
        jdbc.update("update catalog.products set sku = ?, name = ?, category = ?, presentation = ?, cost = ?, price = ? where id = ?",
            input.sku().trim(), input.name().trim(), input.category().trim(), input.presentation().trim(), input.cost(), input.price(), id);
        audit.record(actorId(), "PRODUCT_UPDATE", "PRODUCT", id.toString(), "SUCCESS", Map.of());
    }

    @Transactional
    public void setStatus(UUID id, String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) throw new IllegalArgumentException("status debe ser ACTIVE o INACTIVE");
        if (jdbc.update("update catalog.products set status = ? where id = ?", status, id) == 0) throw new EmptyResultDataAccessException(1);
        audit.record(actorId(), "PRODUCT_STATUS", "PRODUCT", id.toString(), "SUCCESS", Map.of("status", status));
    }

    private boolean exists(String sql, Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args)); }
    private UUID actorId() { try { return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName()); } catch (Exception ignored) { return null; } }
    private Timestamp timestamp() { return Timestamp.from(Instant.now()); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
