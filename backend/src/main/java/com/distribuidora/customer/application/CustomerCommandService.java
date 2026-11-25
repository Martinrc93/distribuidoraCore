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
import java.util.Objects;

@Service
public class CustomerCommandService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CurrentUserAccess access;

    public CustomerCommandService(JdbcTemplate jdbc, AuditService audit, CurrentUserAccess access) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.access = Objects.requireNonNull(access, "currentUser");
    }

    public record CustomerInput(String businessName, String cuitId, String email, String phone, String address, String zone, UUID sellerId, UUID priceListId) { }

    public static void validate(CustomerInput input) {
        if (input == null || blank(input.businessName())) {
            throw new IllegalArgumentException("businessName es obligatorio");
        }
    }

    @Transactional
    public UUID create(CustomerInput input) {
        return create(input, null);
    }

    @Transactional
    public UUID create(CustomerInput input, String requestedKind) {
        PermissionAccess.require("CUSTOMER_CREATE");
        validate(input);
        String kind = requestedKind == null ? "REGULAR" : requestedKind;
        validateKind(kind);
        if ("RESELLER".equals(kind)) requireResellerManagement();
        UUID sellerId = assignedSeller(input.sellerId());
        if (input.priceListId() != null) PermissionAccess.require("CUSTOMER_PRICE_LIST_CHANGE");
        validatePriceList(input.priceListId());
        String cuitId = normalize(input.cuitId());
        if (cuitId != null && exists("select exists(select 1 from customer.customers where tax_id = ?)", cuitId)) {
            throw new IllegalStateException("Ya existe un cliente con esa identificación");
        }
        UUID id = UUID.randomUUID();
        String zone = normalize(input.zone());
        try {
            jdbc.update("""
                insert into customer.customers(id, business_name, tax_id, email, phone, address, zone, zone_id, seller_id, price_list_id, kind, balance, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, (select id from customer.zones where lower(name) = lower(?) limit 1), ?, ?, ?, 0, 'ACTIVE', ?)
                """, id, input.businessName().trim(), cuitId, normalize(input.email()), normalize(input.phone()),
                normalize(input.address()), zone, zone, sellerId, input.priceListId(), kind, timestamp());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalStateException("Ya existe un cliente con esa identificación", e);
        }
        Map<String, Object> details = new HashMap<>();
        details.put("cuitId", cuitId);
        details.put("kind", kind);
        audit.recordWithinTransaction(access.actorIdOrSystem(), "CUSTOMER_CREATE", "CUSTOMER", id.toString(), "SUCCESS", details);
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
        String zone = normalize(input.zone());
        try {
            jdbc.update("update customer.customers set business_name = ?, tax_id = ?, email = ?, phone = ?, address = ?, zone = ?, zone_id = (select id from customer.zones where lower(name) = lower(?) limit 1), seller_id = ?, price_list_id = ? where id = ?",
                input.businessName().trim(), cuitId, normalize(input.email()), normalize(input.phone()),
                normalize(input.address()), zone, zone, sellerId, input.priceListId(), id);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalStateException("Ya existe un cliente con esa identificación", e);
        }
        audit.recordWithinTransaction(access.actorIdOrSystem(), "CUSTOMER_UPDATE", "CUSTOMER", id.toString(), "SUCCESS", Map.of());
    }

    @Transactional
    public void updateContact(UUID id, String email, String phone) {
        PermissionAccess.require("CUSTOMER_EDIT");
        String normalizedEmail = normalize(email);
        String normalizedPhone = normalize(phone);
        if ((normalizedEmail == null) == (normalizedPhone == null)) {
            throw new IllegalArgumentException("Se debe indicar un email o un teléfono");
        }
        lock(id);
        int updated = normalizedEmail != null
            ? jdbc.update("update customer.customers set email = ? where id = ? and email is distinct from ?", normalizedEmail, id, normalizedEmail)
            : jdbc.update("update customer.customers set phone = ? where id = ? and phone is distinct from ?", normalizedPhone, id, normalizedPhone);
        if (updated > 0) {
            audit.recordWithinTransaction(access.actorIdOrSystem(), "CUSTOMER_UPDATE", "CUSTOMER", id.toString(), "SUCCESS",
                Map.of("field", normalizedEmail != null ? "email" : "phone"));
        }
    }

    @Transactional
    public void setStatus(UUID id, String status) {
        PermissionAccess.require("CUSTOMER_STATUS_CHANGE");
        Map<String, Object> locked = lock(id);
        if ("RESELLER".equals(locked.get("kind"))) {
            requireResellerManagement();
        }
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new IllegalArgumentException("status debe ser ACTIVE o INACTIVE");
        }
        if (jdbc.update("update customer.customers set status = ? where id = ?", status, id) == 0) {
            throw new EmptyResultDataAccessException(1);
        }
        audit.recordWithinTransaction(access.actorIdOrSystem(), "CUSTOMER_STATUS", "CUSTOMER", id.toString(), "SUCCESS", Map.of("status", status));
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
        audit.recordWithinTransaction(access.actorIdOrSystem(), "CUSTOMER_PRICE_LIST_ASSIGNMENT", "CUSTOMER", customerId.toString(), "SUCCESS", details);
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
        validateKind(kind);
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
        audit.recordWithinTransaction(access.actorIdOrSystem(), "CUSTOMER_RESELLER_MARK", "CUSTOMER", id.toString(), "SUCCESS",
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
        // Canonical UUID lock order between customer and reseller prevents AB-BA deadlocks.
        Map<String, Object> customer;
        Map<String, Object> reseller = null;
        if (resellerId != null && id != null && id.toString().compareTo(resellerId.toString()) > 0) {
            reseller = jdbc.queryForMap(
                "select kind, status from customer.customers where id = ? for share", resellerId);
            customer = jdbc.queryForMap(
                "select kind, reseller_id from customer.customers where id = ? for update", id);
        } else {
            customer = jdbc.queryForMap(
                "select kind, reseller_id from customer.customers where id = ? for update", id);
            if (resellerId != null) {
                reseller = jdbc.queryForMap(
                    "select kind, status from customer.customers where id = ? for share", resellerId);
            }
        }
        if ("RESELLER".equals(customer.get("kind"))) {
            throw new ResellerConflictException("Un revendedor no puede tener revendedor.");
        }
        UUID previous = (UUID) customer.get("reseller_id");
        if (java.util.Objects.equals(previous, resellerId)) {
            return new ResellerAssignment(id, resellerId, pendingOrders(id));
        }
        if (reseller != null) {
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
        audit.recordWithinTransaction(access.actorIdOrSystem(), "CUSTOMER_RESELLER_ASSIGN", "CUSTOMER", id.toString(), "SUCCESS", details);
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
        Map<String, Object> row = jdbc.queryForMap("select seller_id, price_list_id, kind from customer.customers where id = ? for update", customerId);
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
            "select status from catalog.price_lists where id = ? for share", String.class, priceListId);
        if (!"ACTIVE".equals(status)) throw new IllegalStateException("La lista de precios no está activa");
    }

    private static void validateKind(String kind) {
        if (!"RESELLER".equals(kind) && !"REGULAR".equals(kind)) {
            throw new IllegalArgumentException("kind debe ser RESELLER o REGULAR");
        }
    }


    private Timestamp timestamp() { return Timestamp.from(Instant.now()); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalize(String value) { return blank(value) ? null : value.trim(); }
}
