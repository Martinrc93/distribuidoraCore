package com.distribuidora.customer.application;

import com.distribuidora.audit.application.AuditService;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomerCommandService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public CustomerCommandService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public record CustomerInput(String businessName, String cuitId, UUID sellerId) { }

    public static void validate(CustomerInput input) {
        if (input == null || blank(input.businessName())) {
            throw new IllegalArgumentException("businessName es obligatorio");
        }
    }

    @Transactional
    public UUID create(CustomerInput input) {
        validate(input);
        String cuitId = normalize(input.cuitId());
        if (cuitId != null && exists("select exists(select 1 from customer.customers where tax_id = ?)", cuitId)) {
            throw new IllegalStateException("Ya existe un cliente con esa identificación");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into customer.customers(id, business_name, tax_id, seller_id, balance, status, created_at)
            values (?, ?, ?, ?, 0, 'ACTIVE', ?)
            """, id, input.businessName().trim(), cuitId, input.sellerId(), timestamp());
        Map<String, Object> details = new HashMap<>();
        details.put("cuitId", cuitId);
        audit.record(actorId(), "CUSTOMER_CREATE", "CUSTOMER", id.toString(), "SUCCESS", details);
        return id;
    }

    @Transactional
    public void update(UUID id, CustomerInput input) {
        validate(input);
        String cuitId = normalize(input.cuitId());
        if (!exists("select exists(select 1 from customer.customers where id = ?)", id)) {
            throw new EmptyResultDataAccessException(1);
        }
        if (cuitId != null && exists("select exists(select 1 from customer.customers where tax_id = ? and id <> ?)", cuitId, id)) {
            throw new IllegalStateException("Ya existe un cliente con esa identificación");
        }
        jdbc.update("update customer.customers set business_name = ?, tax_id = ?, seller_id = ? where id = ?",
            input.businessName().trim(), cuitId, input.sellerId(), id);
        audit.record(actorId(), "CUSTOMER_UPDATE", "CUSTOMER", id.toString(), "SUCCESS", Map.of());
    }

    @Transactional
    public void setStatus(UUID id, String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new IllegalArgumentException("status debe ser ACTIVE o INACTIVE");
        }
        if (jdbc.update("update customer.customers set status = ? where id = ?", status, id) == 0) {
            throw new EmptyResultDataAccessException(1);
        }
        audit.record(actorId(), "CUSTOMER_STATUS", "CUSTOMER", id.toString(), "SUCCESS", Map.of("status", status));
    }

    @Transactional
    public void assignPriceList(UUID customerId, UUID priceListId) {
        if (!exists("select exists(select 1 from customer.customers where id = ?)", customerId)) {
            throw new EmptyResultDataAccessException(1);
        }
        if (priceListId != null) {
            String status = jdbc.queryForObject(
                "select status from catalog.price_lists where id = ?", String.class, priceListId);
            if (!"ACTIVE".equals(status)) {
                throw new IllegalStateException("La lista de precios no está activa");
            }
        }
        jdbc.update("update customer.customers set price_list_id = ? where id = ?", priceListId, customerId);
        Map<String, Object> details = new HashMap<>();
        details.put("priceListId", priceListId);
        audit.record(actorId(), "CUSTOMER_PRICE_LIST_ASSIGNMENT", "CUSTOMER", customerId.toString(), "SUCCESS", details);
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args));
    }

    private UUID actorId() {
        try { return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName()); }
        catch (Exception ignored) { return null; }
    }

    private Timestamp timestamp() { return Timestamp.from(Instant.now()); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalize(String value) { return blank(value) ? null : value.trim(); }
}
