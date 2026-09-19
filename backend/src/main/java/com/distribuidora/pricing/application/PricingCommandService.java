package com.distribuidora.pricing.application;

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
public class PricingCommandService {
    private static final long CREATE_LIST_LOCK_KEY = 4_839_271_106L;

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public PricingCommandService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional
    public UUID createList(String code, String name) {
        String normalizedCode = validateText(code, "code", 40);
        String normalizedName = validateText(name, "name", 120);
        jdbc.queryForObject("select pg_advisory_xact_lock(?)", Object.class, CREATE_LIST_LOCK_KEY);
        if (Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from catalog.price_lists where code = ?)", Boolean.class, normalizedCode))) {
            throw new IllegalStateException("Ya existe una lista con ese código");
        }
        Integer count = jdbc.queryForObject("select count(*) from catalog.price_lists", Integer.class);
        if (count != null && count >= 10) {
            throw new IllegalStateException("No se pueden crear más de diez listas de precios");
        }

        UUID id = UUID.randomUUID();
        Timestamp now = timestamp();
        jdbc.update("insert into catalog.price_lists(id, code, name, status, is_default, created_at, updated_at) values (?, ?, ?, 'ACTIVE', false, ?, ?)",
            id, normalizedCode, normalizedName, now, now);
        audit.record(actorId(), "PRICELIST_CREATE", "PRICE_LIST", id.toString(), "SUCCESS",
            Map.of("code", normalizedCode));
        return id;
    }

    @Transactional
    public void renameList(UUID listId, String name) {
        requireId(listId, "listId");
        String normalizedName = validateText(name, "name", 120);
        if (jdbc.update("update catalog.price_lists set name = ?, updated_at = ? where id = ?",
            normalizedName, timestamp(), listId) == 0) {
            throw new EmptyResultDataAccessException(1);
        }
        audit.record(actorId(), "PRICELIST_UPDATE", "PRICE_LIST", listId.toString(), "SUCCESS",
            Map.of("name", normalizedName));
    }

    @Transactional
    public void setListStatus(UUID listId, String status) {
        requireId(listId, "listId");
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new IllegalArgumentException("status debe ser ACTIVE o INACTIVE");
        }
        jdbc.queryForObject("select status from catalog.price_lists where id = ?", String.class, listId);
        if ("INACTIVE".equals(status)
            && Boolean.TRUE.equals(jdbc.queryForObject(
                "select is_default from catalog.price_lists where id = ?", Boolean.class, listId))) {
            throw new IllegalStateException("La lista default no puede desactivarse");
        }
        jdbc.update("update catalog.price_lists set status = ?, updated_at = ? where id = ?",
            status, timestamp(), listId);
        audit.record(actorId(), "PRICELIST_STATUS", "PRICE_LIST", listId.toString(), "SUCCESS",
            Map.of("status", status));
    }

    @Transactional
    public void setProductPrice(UUID listId, UUID productId, BigDecimal price) {
        requireId(listId, "listId");
        requireId(productId, "productId");
        if (price == null || price.signum() < 0 || price.scale() > 4 || price.precision() > 19
            || price.precision() - price.scale() > 15) {
            throw new IllegalArgumentException("price debe ser un valor no negativo con hasta cuatro decimales");
        }
        String listStatus = jdbc.queryForObject(
            "select status from catalog.price_lists where id = ?", String.class, listId);
        if (!"ACTIVE".equals(listStatus)) {
            throw new IllegalStateException("La lista de precios no está activa");
        }
        String productStatus = jdbc.queryForObject(
            "select status from catalog.products where id = ?", String.class, productId);
        if (!"ACTIVE".equals(productStatus)) {
            throw new IllegalStateException("El producto no está activo");
        }

        Timestamp now = timestamp();
        jdbc.update("insert into catalog.product_prices(price_list_id, product_id, price, created_at, updated_at) values (?, ?, ?, ?, ?) on conflict (price_list_id, product_id) do update set price = excluded.price, updated_at = excluded.updated_at",
            listId, productId, price, now, now);
        audit.record(actorId(), "PRODUCT_PRICE_UPDATE", "PRODUCT_PRICE", listId + ":" + productId, "SUCCESS",
            Map.of("price", price));
    }

    private String validateText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(field + " es obligatorio y no puede superar " + maxLength + " caracteres");
        }
        return value.trim();
    }

    private void requireId(UUID id, String field) {
        if (id == null) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
    }

    private UUID actorId() {
        try {
            return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
        } catch (Exception exception) {
            throw new IllegalArgumentException("El actor autenticado no tiene un UUID válido", exception);
        }
    }

    private Timestamp timestamp() {
        return Timestamp.from(Instant.now());
    }
}
