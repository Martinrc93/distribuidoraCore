package com.distribuidora.seller.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class SellerCommandService {
    public interface CreateSellerCommand { UUID userId(); String displayName(); }
    public interface UpdateSellerCommand { String displayName(); }
    public interface ReassignCustomersCommand {
        UUID sourceSellerId();
        UUID targetSellerId();
        List<UUID> customerIds();
        Boolean reassignPendingOrders();
    }
    public interface ReassignOrdersCommand {
        UUID targetSellerId();
        List<UUID> orderIds();
        Boolean onlyPending();
    }

    public record SellerView(UUID id, UUID userId, String displayName, String email, String status,
                             Instant createdAt, long assignedCustomersCount) { }
    public record ReassignCustomersResult(UUID sourceSellerId, UUID targetSellerId,
                                         int reassignedCustomersCount, int reassignedOrdersCount) { }
    public record ReassignOrdersResult(UUID targetSellerId, int reassignedOrdersCount) { }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;
    private final SellerQueryService queries;

    @Autowired
    public SellerCommandService(JdbcTemplate jdbc, AuditService audit, CurrentUserAccess currentUser,
                                SellerQueryService queries) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser");
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    public SellerCommandService(JdbcTemplate jdbc, AuditService audit, CurrentUserAccess currentUser) {
        this(jdbc, audit, currentUser, new SellerQueryService(jdbc));
    }

    @Transactional
    public UUID create(CreateSellerCommand request) {
        if (request == null || request.userId() == null || request.displayName() == null || request.displayName().isBlank()) {
            throw new IllegalArgumentException("userId y displayName son obligatorios");
        }

        UUID userId = request.userId();
        String displayName = request.displayName().trim();
        if (displayName.length() > 160) {
            throw new IllegalArgumentException("displayName no puede superar los 160 caracteres");
        }

        String userStatus;
        try {
            userStatus = jdbc.queryForObject("select status from identity.users where id = ? for update", String.class, userId);
        } catch (EmptyResultDataAccessException missing) {
            throw new EmptyResultDataAccessException("El usuario no existe", 1);
        }
        if ("BLOCKED".equalsIgnoreCase(userStatus) || "DISABLED".equalsIgnoreCase(userStatus)) {
            throw new IllegalStateException("No se puede crear un perfil de vendedor para un usuario inactivo o bloqueado");
        }
        boolean isAdmin = Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from identity.user_roles ur join identity.roles r on r.id = ur.role_id where ur.user_id = ? and r.code = 'ADMIN')",
            Boolean.class, userId));
        if (isAdmin) {
            throw new IllegalStateException("Un usuario ADMIN no puede tener perfil de vendedor");
        }

        boolean alreadyHasProfile = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from seller.seller_profiles where user_id = ?)", Boolean.class, userId)
        );
        if (alreadyHasProfile) {
            throw new IllegalStateException("El usuario ya tiene un perfil de vendedor asignado");
        }

        jdbc.update(
            """
            insert into identity.user_roles(user_id, role_id)
            select ?, id from identity.roles where code = 'SELLER'
              and not exists (
                select 1 from identity.user_roles ur join identity.roles r on r.id = ur.role_id
                where ur.user_id = ? and r.code not in ('ADMIN', 'SELLER')
              )
            on conflict do nothing
            """, userId, userId
        );

        UUID sellerId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        try {
            jdbc.update(
                "insert into seller.seller_profiles(id, user_id, display_name, status, created_at) values (?, ?, ?, 'ACTIVE', ?)",
                sellerId, userId, displayName, now
            );
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("El usuario ya tiene un perfil de vendedor asignado", e);
        }
        jdbc.update("update identity.users set session_version = session_version + 1, version = version + 1, updated_at = ? where id = ?", now, userId);
        jdbc.update("update identity.refresh_tokens set revoked_at = ? where user_id = ? and revoked_at is null", now, userId);

        audit.recordWithinTransaction(
            currentUser.actorIdOrSystem(),
            "SELLER_CREATE",
            "SELLER",
            sellerId.toString(),
            "SUCCESS",
            Map.of("userId", userId.toString(), "displayName", displayName)
        );

        return sellerId;
    }

    @Transactional
    public void update(UUID id, UpdateSellerCommand request) {
        if (request == null || request.displayName() == null || request.displayName().isBlank()) {
            throw new IllegalArgumentException("displayName es obligatorio");
        }

        String displayName = request.displayName().trim();
        if (displayName.length() > 160) {
            throw new IllegalArgumentException("displayName no puede superar los 160 caracteres");
        }

        boolean exists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from seller.seller_profiles where id = ?)", Boolean.class, id)
        );
        if (!exists) {
            throw new EmptyResultDataAccessException("El perfil de vendedor no existe", 1);
        }

        jdbc.update("update seller.seller_profiles set display_name = ? where id = ?", displayName, id);

        audit.recordWithinTransaction(
            currentUser.actorIdOrSystem(),
            "SELLER_UPDATE",
            "SELLER",
            id.toString(),
            "SUCCESS",
            Map.of("displayName", displayName)
        );
    }

    @Transactional
    public void setStatus(UUID id, String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new IllegalArgumentException("status debe ser ACTIVE o INACTIVE");
        }

        Map<String, Object> existing;
        try {
            existing = jdbc.queryForMap("select user_id, status from seller.seller_profiles where id = ? for update", id);
        } catch (EmptyResultDataAccessException missing) {
            throw new EmptyResultDataAccessException("El perfil de vendedor no existe", 1);
        }

        if (!status.equals(existing.get("status"))) {
            jdbc.update("update seller.seller_profiles set status = ? where id = ?", status, id);
            Timestamp now = Timestamp.from(Instant.now());
            UUID userId = (UUID) existing.get("user_id");
            jdbc.update("update identity.users set session_version = session_version + 1, version = version + 1, updated_at = ? where id = ?", now, userId);
            jdbc.update("update identity.refresh_tokens set revoked_at = ? where user_id = ? and revoked_at is null", now, userId);
            audit.recordWithinTransaction(
                currentUser.actorIdOrSystem(),
                "SELLER_STATUS",
                "SELLER",
                id.toString(),
                "SUCCESS",
                Map.of("status", status)
            );
        }
    }

    @Transactional
    public void activate(UUID id) {
        setStatus(id, "ACTIVE");
    }

    @Transactional
    public void deactivate(UUID id) {
        setStatus(id, "INACTIVE");
    }

    public Optional<UUID> findSellerIdByUserId(UUID userId) {
        return queries.findSellerIdByUserId(userId);
    }

    public Optional<SellerView> findSellerById(UUID id) {
        return queries.findSellerById(id);
    }

    @Transactional
    public ReassignCustomersResult reassignCustomers(ReassignCustomersCommand request) {
        if (request == null || request.sourceSellerId() == null || request.targetSellerId() == null) {
            throw new IllegalArgumentException("sourceSellerId y targetSellerId son obligatorios");
        }
        if (request.sourceSellerId().equals(request.targetSellerId())) {
            throw new IllegalArgumentException("El vendedor origen y el vendedor destino no pueden ser el mismo");
        }
        if (request.customerIds() != null) {
            if (request.customerIds().isEmpty()) {
                throw new IllegalArgumentException("customerIds no puede estar vacío; omitilo para reasignar toda la cartera");
            }
            if (request.customerIds().size() > 1000 || request.customerIds().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("customerIds admite entre 1 y 1000 identificadores válidos");
            }
        }

        boolean sourceExists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from seller.seller_profiles where id = ?)", Boolean.class, request.sourceSellerId())
        );
        if (!sourceExists) {
            throw new EmptyResultDataAccessException("El vendedor origen no existe", 1);
        }

        boolean targetExists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from seller.seller_profiles where id = ?)", Boolean.class, request.targetSellerId())
        );
        if (!targetExists) {
            throw new EmptyResultDataAccessException("El vendedor destino no existe", 1);
        }

        String targetStatus = jdbc.queryForObject("select status from seller.seller_profiles where id = ? for share", String.class, request.targetSellerId());
        if (!"ACTIVE".equalsIgnoreCase(targetStatus)) {
            throw new IllegalStateException("El vendedor destino debe estar activo");
        }

        boolean hasCustomerFilter = request.customerIds() != null && !request.customerIds().isEmpty();
        int reassignedOrders = 0;
        boolean reassignOrders = request.reassignPendingOrders() == null || Boolean.TRUE.equals(request.reassignPendingOrders());
        // Canonical lock order: lock and update orders.orders before customer.customers, each ordered by id.
        if (reassignOrders) {
            if (!hasCustomerFilter) {
                reassignedOrders = jdbc.update("""
                    with locked as (
                        select id from orders.orders
                        where seller_id = ? and status = 'CONFIRMED'
                        order by id for update
                    )
                    update orders.orders o set seller_id = ?
                    from locked where o.id = locked.id
                    """, request.sourceSellerId(), request.targetSellerId());
            } else {
                String placeholders = String.join(",", Collections.nCopies(request.customerIds().size(), "?"));
                List<Object> params = new ArrayList<>();
                params.add(request.sourceSellerId());
                params.addAll(request.customerIds());
                params.add(request.targetSellerId());
                reassignedOrders = jdbc.update("""
                    with locked as (
                        select id from orders.orders
                        where seller_id = ? and status = 'CONFIRMED' and customer_id in (""" + placeholders + """
                        )
                        order by id for update
                    )
                    update orders.orders o set seller_id = ?
                    from locked where o.id = locked.id
                    """, params.toArray());
            }
        }

        int reassignedCustomers;
        if (!hasCustomerFilter) {
            reassignedCustomers = jdbc.update("""
                with locked as (
                    select id from customer.customers
                    where seller_id = ?
                    order by id for update
                )
                update customer.customers c set seller_id = ?
                from locked where c.id = locked.id
                """, request.sourceSellerId(), request.targetSellerId());
        } else {
            String placeholders = String.join(",", Collections.nCopies(request.customerIds().size(), "?"));
            List<Object> params = new ArrayList<>();
            params.add(request.sourceSellerId());
            params.addAll(request.customerIds());
            params.add(request.targetSellerId());
            reassignedCustomers = jdbc.update("""
                with locked as (
                    select id from customer.customers
                    where seller_id = ? and id in (""" + placeholders + """
                    )
                    order by id for update
                )
                update customer.customers c set seller_id = ?
                from locked where c.id = locked.id
                """, params.toArray());
        }

        audit.recordWithinTransaction(
            currentUser.actorIdOrSystem(),
            "SELLER_CUSTOMER_REASSIGNMENT",
            "SELLER",
            request.targetSellerId().toString(),
            "SUCCESS",
            Map.of(
                "sourceSellerId", request.sourceSellerId().toString(),
                "targetSellerId", request.targetSellerId().toString(),
                "reassignedCustomersCount", reassignedCustomers,
                "reassignedOrdersCount", reassignedOrders
            )
        );

        return new ReassignCustomersResult(
            request.sourceSellerId(),
            request.targetSellerId(),
            reassignedCustomers,
            reassignedOrders
        );
    }

    @Transactional
    public ReassignOrdersResult reassignOrders(ReassignOrdersCommand request) {
        if (request == null || request.targetSellerId() == null || request.orderIds() == null || request.orderIds().isEmpty()) {
            throw new IllegalArgumentException("targetSellerId y orderIds son obligatorios");
        }
        if (request.orderIds().size() > 1000 || request.orderIds().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("orderIds admite entre 1 y 1000 identificadores válidos");
        }

        boolean targetExists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from seller.seller_profiles where id = ?)", Boolean.class, request.targetSellerId())
        );
        if (!targetExists) {
            throw new EmptyResultDataAccessException("El vendedor destino no existe", 1);
        }

        String targetStatus = jdbc.queryForObject("select status from seller.seller_profiles where id = ? for share", String.class, request.targetSellerId());
        if (!"ACTIVE".equalsIgnoreCase(targetStatus)) {
            throw new IllegalStateException("El vendedor destino debe estar activo");
        }

        boolean onlyPending = request.onlyPending() == null || Boolean.TRUE.equals(request.onlyPending());
        if (!onlyPending) {
            throw new IllegalArgumentException("Solo se pueden reasignar pedidos CONFIRMED");
        }
        String placeholders = String.join(",", Collections.nCopies(request.orderIds().size(), "?"));
        List<Object> params = new ArrayList<>(request.orderIds());
        params.add(request.targetSellerId());

        String sql = """
            with locked as (
                select id from orders.orders
                where id in (""" + placeholders + """
                ) and status = 'CONFIRMED'
                order by id for update
            )
            update orders.orders o set seller_id = ?
            from locked where o.id = locked.id
            """;

        int reassignedOrders = jdbc.update(sql, params.toArray());

        audit.recordWithinTransaction(
            currentUser.actorIdOrSystem(),
            "SELLER_ORDER_REASSIGNMENT",
            "SELLER",
            request.targetSellerId().toString(),
            "SUCCESS",
            Map.of(
                "targetSellerId", request.targetSellerId().toString(),
                "reassignedOrdersCount", reassignedOrders
            )
        );

        return new ReassignOrdersResult(
            request.targetSellerId(),
            reassignedOrders
        );
    }
}
