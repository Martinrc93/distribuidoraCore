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

    /** Result of a reseller assignment: confirmed orders not yet delivered keep the account that owes them. */
    public record ResellerAssignment(UUID customerId, UUID resellerId, long pendingOrdersKept) { }

    /**
     * Marks or unmarks a customer as reseller. A customer associated with a reseller cannot become one,
     * and a reseller keeps the mark while customers are associated with it.
     */
    @Transactional
    public void changeKind(UUID id, String kind) {
        requireResellerManagement();
        if (!"RESELLER".equals(kind) && !"REGULAR".equals(kind)) {
            throw new IllegalArgumentException("kind debe ser RESELLER o REGULAR");
        }
        Map<String, Object> customer = jdbc.queryForMap(
            "select kind, reseller_id from customer.customers where id = ? for update", id);
        if (kind.equals(customer.get("kind"))) return;
        if ("RESELLER".equals(kind) && customer.get("reseller_id") != null) {
            throw new ResellerConflictException("Un cliente asociado a un revendedor no puede ser revendedor.");
        }
        if ("REGULAR".equals(kind) && exists("select exists(select 1 from customer.customers where reseller_id = ?)", id)) {
            throw new ResellerConflictException("El revendedor tiene clientes asociados. Reasignalos antes de quitar la marca.");
        }
        // Commission plans need the mark (V53 enforces it) and pending commissions are settled from the reseller list.
        if ("REGULAR".equals(kind) && exists("""
            select exists(select 1 from app.reseller_commission_plans where customer_id = ?)
                or exists(select 1 from sale.commission_entries where reseller_id = ? and settlement_id is null)
            """, id, id)) {
            throw new ResellerConflictException(
                "El revendedor tiene plan de comisión o comisiones sin liquidar. Quitá el plan y liquidalas antes de quitar la marca.");
        }
        jdbc.update("update customer.customers set kind = ? where id = ?", kind, id);
        audit.recordWithinTransaction(actorId(), "CUSTOMER_RESELLER_MARK", "CUSTOMER", id.toString(), "SUCCESS",
            Map.of("kind", kind));
    }

    /**
     * Associates a customer with a reseller, or removes the association with {@code null}. Only new orders
     * change their account: confirmed orders, their debt and the customer's own debt keep their account.
     */
    @Transactional
    public ResellerAssignment assignReseller(UUID id, UUID resellerId) {
        requireResellerManagement();
        if (id != null && id.equals(resellerId)) {
            throw new IllegalArgumentException("Un cliente no puede ser su propio revendedor");
        }
        // Lock order: the customer, then the reseller (the same order confirmations follow).
        Map<String, Object> customer = jdbc.queryForMap(
            "select kind, reseller_id from customer.customers where id = ? for update", id);
        if ("RESELLER".equals(customer.get("kind"))) {
            throw new ResellerConflictException("Un revendedor no puede tener revendedor.");
        }
        UUID previous = (UUID) customer.get("reseller_id");
        if (java.util.Objects.equals(previous, resellerId)) {
            return new ResellerAssignment(id, resellerId, pendingOrders(id));
        }
        if (resellerId != null) {
            Map<String, Object> reseller = jdbc.queryForMap(
                "select kind, status from customer.customers where id = ? for share", resellerId);
            if (!"RESELLER".equals(reseller.get("kind"))) {
                throw new ResellerConflictException("El cliente elegido no es revendedor.");
            }
            if (!"ACTIVE".equals(reseller.get("status"))) {
                throw new ResellerConflictException("El revendedor elegido no está activo.");
            }
        }
        jdbc.update("update customer.customers set reseller_id = ?, reseller_kind = ? where id = ?",
            resellerId, resellerId == null ? null : "RESELLER", id);
        long kept = pendingOrders(id);
        Map<String, Object> details = new HashMap<>();
        details.put("previousResellerId", previous == null ? null : previous.toString());
        details.put("resellerId", resellerId == null ? null : resellerId.toString());
        details.put("pendingOrdersKept", kept);
        audit.recordWithinTransaction(actorId(), "CUSTOMER_RESELLER_ASSIGN", "CUSTOMER", id.toString(), "SUCCESS", details);
        return new ResellerAssignment(id, resellerId, kept);
    }

    private long pendingOrders(UUID customerId) {
        Long count = jdbc.queryForObject(
            "select count(*) from orders.orders where customer_id = ? and status = 'CONFIRMED'", Long.class, customerId);
        return count == null ? 0 : count;
    }

    // Reserved to administrators, like other global operations: both ADMIN_ALL and the explicit permission.
    private void requireResellerManagement() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean granted = access.isAdmin() && authentication != null && authentication.getAuthorities().stream()
            .anyMatch(authority -> "RESELLER_MANAGE".equals(authority.getAuthority()));
        if (!granted) throw new org.springframework.security.access.AccessDeniedException("Se requiere el permiso RESELLER_MANAGE");
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
