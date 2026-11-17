package com.distribuidora.customer.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.shared.security.CurrentUserAccess;
import com.distribuidora.shared.security.PermissionAccess;
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
    private final CurrentUserAccess access;

    public CustomerCommandService(JdbcTemplate jdbc, AuditService audit, CurrentUserAccess access) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.access = access;
    }

    public record CustomerInput(String businessName, String cuitId, String email, String phone, String address, String zone, UUID sellerId, UUID priceListId) { }

    public static void validate(CustomerInput input) {
        if (input == null || blank(input.businessName())) {
            throw new IllegalArgumentException("businessName es obligatorio");
        }
    }

    @Transactional
    public UUID create(CustomerInput input) {
        PermissionAccess.require("CUSTOMER_CREATE");
        validate(input);
        UUID sellerId = assignedSeller(input.sellerId());
        if (input.priceListId() != null) PermissionAccess.require("CUSTOMER_PRICE_LIST_CHANGE");
        validatePriceList(input.priceListId());
        String cuitId = normalize(input.cuitId());
        if (cuitId != null && exists("select exists(select 1 from customer.customers where tax_id = ?)", cuitId)) {
            throw new IllegalStateException("Ya existe un cliente con esa identificación");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into customer.customers(id, business_name, tax_id, email, phone, address, zone, seller_id, price_list_id, balance, status, created_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'ACTIVE', ?)
            """, id, input.businessName().trim(), cuitId, normalize(input.email()), normalize(input.phone()),
            normalize(input.address()), normalize(input.zone()), sellerId, input.priceListId(), timestamp());
        Map<String, Object> details = new HashMap<>();
        details.put("cuitId", cuitId);
        audit.recordWithinTransaction(actorId(), "CUSTOMER_CREATE", "CUSTOMER", id.toString(), "SUCCESS", details);
        return id;
    }

    @Transactional
    public void update(UUID id, CustomerInput input) {
        PermissionAccess.require("CUSTOMER_EDIT");
        validate(input);
        Map<String, Object> previous = lock(id);
        UUID sellerId = assignedSeller(input.sellerId());
        String cuitId = normalize(input.cuitId());
        if (!exists("select exists(select 1 from customer.customers where id = ?)", id)) {
            throw new EmptyResultDataAccessException(1);
        }
        if (cuitId != null && exists("select exists(select 1 from customer.customers where tax_id = ? and id <> ?)", cuitId, id)) {
            throw new IllegalStateException("Ya existe un cliente con esa identificación");
        }
        if (!java.util.Objects.equals(input.priceListId(), previous.get("price_list_id"))) {
            PermissionAccess.require("CUSTOMER_PRICE_LIST_CHANGE");
            validatePriceList(input.priceListId());
        }
        jdbc.update("update customer.customers set business_name = ?, tax_id = ?, email = ?, phone = ?, address = ?, zone = ?, seller_id = ?, price_list_id = ? where id = ?",
            input.businessName().trim(), cuitId, normalize(input.email()), normalize(input.phone()),
            normalize(input.address()), normalize(input.zone()), sellerId, input.priceListId(), id);
        audit.recordWithinTransaction(actorId(), "CUSTOMER_UPDATE", "CUSTOMER", id.toString(), "SUCCESS", Map.of());
    }

    @Transactional
    public void setStatus(UUID id, String status) {
        PermissionAccess.require("CUSTOMER_STATUS_CHANGE");
        lock(id);
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new IllegalArgumentException("status debe ser ACTIVE o INACTIVE");
        }
        if (jdbc.update("update customer.customers set status = ? where id = ?", status, id) == 0) {
            throw new EmptyResultDataAccessException(1);
        }
        audit.recordWithinTransaction(actorId(), "CUSTOMER_STATUS", "CUSTOMER", id.toString(), "SUCCESS", Map.of("status", status));
    }

    @Transactional
    public void assignPriceList(UUID customerId, UUID priceListId) {
        PermissionAccess.require("CUSTOMER_PRICE_LIST_CHANGE");
        lock(customerId);
        if (!exists("select exists(select 1 from customer.customers where id = ?)", customerId)) {
            throw new EmptyResultDataAccessException(1);
        }
        validatePriceList(priceListId);
        jdbc.update("update customer.customers set price_list_id = ? where id = ?", priceListId, customerId);
        Map<String, Object> details = new HashMap<>();
        details.put("priceListId", priceListId);
        audit.recordWithinTransaction(actorId(), "CUSTOMER_PRICE_LIST_ASSIGNMENT", "CUSTOMER", customerId.toString(), "SUCCESS", details);
    }

    private Map<String, Object> lock(UUID customerId) {
        Map<String, Object> row = jdbc.queryForMap("select seller_id, price_list_id from customer.customers where id = ? for update", customerId);
        access.requireCustomerAccess(customerId);
        return row;
    }

    private UUID assignedSeller(UUID requested) {
        if (access.isAdmin()) return requested;
        UUID own = access.requireSellerProfile();
        if (requested != null && !own.equals(requested))
            throw new org.springframework.security.access.AccessDeniedException("Solo se pueden asignar clientes al perfil propio");
        return own;
    }

    private boolean exists(String sql, Object... args) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, args));
    }

    private void validatePriceList(UUID priceListId) {
        if (priceListId == null) return;
        String status = jdbc.queryForObject(
            "select status from catalog.price_lists where id = ?", String.class, priceListId);
        if (!"ACTIVE".equals(status)) throw new IllegalStateException("La lista de precios no está activa");
    }

    private UUID actorId() {
        try { return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName()); }
        catch (Exception ignored) { return null; }
    }

    private Timestamp timestamp() { return Timestamp.from(Instant.now()); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalize(String value) { return blank(value) ? null : value.trim(); }
}
