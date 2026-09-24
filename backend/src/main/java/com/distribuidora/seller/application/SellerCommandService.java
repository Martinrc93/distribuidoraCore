package com.distribuidora.seller.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.seller.api.SellerDtos;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SellerCommandService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;

    public SellerCommandService(JdbcTemplate jdbc, AuditService audit, CurrentUserAccess currentUser) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional
    public UUID create(SellerDtos.CreateSellerRequest request) {
        if (request == null || request.userId() == null || request.displayName() == null || request.displayName().isBlank()) {
            throw new IllegalArgumentException("userId y displayName son obligatorios");
        }

        UUID userId = request.userId();
        String displayName = request.displayName().trim();

        boolean userExists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from identity.users where id = ?)", Boolean.class, userId)
        );
        if (!userExists) {
            throw new EmptyResultDataAccessException("El usuario no existe", 1);
        }

        String userStatus = jdbc.queryForObject("select status from identity.users where id = ?", String.class, userId);
        if ("BLOCKED".equalsIgnoreCase(userStatus) || "DISABLED".equalsIgnoreCase(userStatus)) {
            throw new IllegalStateException("No se puede crear un perfil de vendedor para un usuario inactivo o bloqueado");
        }

        boolean alreadyHasProfile = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from seller.seller_profiles where user_id = ?)", Boolean.class, userId)
        );
        if (alreadyHasProfile) {
            throw new IllegalStateException("El usuario ya tiene un perfil de vendedor asignado");
        }

        jdbc.update(
            "insert into identity.user_roles(user_id, role_id) select ?, id from identity.roles where code = 'SELLER' on conflict do nothing",
            userId
        );

        UUID sellerId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbc.update(
            "insert into seller.seller_profiles(id, user_id, display_name, status, created_at) values (?, ?, ?, 'ACTIVE', ?)",
            sellerId, userId, displayName, now
        );

        audit.record(
            actorId(),
            "SELLER_CREATE",
            "SELLER",
            sellerId.toString(),
            "SUCCESS",
            Map.of("userId", userId.toString(), "displayName", displayName)
        );

        return sellerId;
    }

    @Transactional
    public void update(UUID id, SellerDtos.UpdateSellerRequest request) {
        if (request == null || request.displayName() == null || request.displayName().isBlank()) {
            throw new IllegalArgumentException("displayName es obligatorio");
        }

        boolean exists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from seller.seller_profiles where id = ?)", Boolean.class, id)
        );
        if (!exists) {
            throw new EmptyResultDataAccessException("El perfil de vendedor no existe", 1);
        }

        String displayName = request.displayName().trim();
        jdbc.update("update seller.seller_profiles set display_name = ? where id = ?", displayName, id);

        audit.record(
            actorId(),
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

        int rows = jdbc.update("update seller.seller_profiles set status = ? where id = ?", status, id);
        if (rows == 0) {
            throw new EmptyResultDataAccessException("El perfil de vendedor no existe", 1);
        }

        audit.record(
            actorId(),
            "SELLER_STATUS",
            "SELLER",
            id.toString(),
            "SUCCESS",
            Map.of("status", status)
        );
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
        List<UUID> ids = jdbc.query(
            "select id from seller.seller_profiles where user_id = ?",
            (rs, i) -> rs.getObject(1, UUID.class),
            userId
        );
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.getFirst());
    }

    public Optional<SellerDtos.SellerResponse> findSellerById(UUID id) {
        List<SellerDtos.SellerResponse> sellers = jdbc.query("""
            select sp.id, sp.user_id as "userId", sp.display_name as "displayName",
                   u.email, sp.status, sp.created_at as "createdAt",
                   (select count(*) from customer.customers c where c.seller_id = sp.id) as "assignedCustomersCount"
            from seller.seller_profiles sp
            join identity.users u on u.id = sp.user_id
            where sp.id = ?
            """,
            (rs, i) -> new SellerDtos.SellerResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("userId", UUID.class),
                rs.getString("displayName"),
                rs.getString("email"),
                rs.getString("status"),
                rs.getTimestamp("createdAt").toInstant(),
                rs.getLong("assignedCustomersCount")
            ),
            id
        );
        return sellers.isEmpty() ? Optional.empty() : Optional.of(sellers.getFirst());
    }

    @Transactional
    public SellerDtos.ReassignCustomersResponse reassignCustomers(SellerDtos.ReassignCustomersRequest request) {
        if (request == null || request.sourceSellerId() == null || request.targetSellerId() == null) {
            throw new IllegalArgumentException("sourceSellerId y targetSellerId son obligatorios");
        }
        if (request.sourceSellerId().equals(request.targetSellerId())) {
            throw new IllegalArgumentException("El vendedor origen y el vendedor destino no pueden ser el mismo");
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

        String targetStatus = jdbc.queryForObject("select status from seller.seller_profiles where id = ?", String.class, request.targetSellerId());
        if (!"ACTIVE".equalsIgnoreCase(targetStatus)) {
            throw new IllegalStateException("El vendedor destino debe estar activo");
        }

        int reassignedCustomers;
        if (request.customerIds() == null || request.customerIds().isEmpty()) {
            reassignedCustomers = jdbc.update(
                "update customer.customers set seller_id = ? where seller_id = ?",
                request.targetSellerId(), request.sourceSellerId()
            );
        } else {
            String placeholders = String.join(",", java.util.Collections.nCopies(request.customerIds().size(), "?"));
            List<Object> params = new java.util.ArrayList<>();
            params.add(request.targetSellerId());
            params.add(request.sourceSellerId());
            params.addAll(request.customerIds());
            reassignedCustomers = jdbc.update(
                "update customer.customers set seller_id = ? where seller_id = ? and id in (" + placeholders + ")",
                params.toArray()
            );
        }

        int reassignedOrders = 0;
        boolean reassignOrders = request.reassignPendingOrders() == null || Boolean.TRUE.equals(request.reassignPendingOrders());
        if (reassignOrders) {
            if (request.customerIds() == null || request.customerIds().isEmpty()) {
                reassignedOrders = jdbc.update(
                    "update orders.orders set seller_id = ? where seller_id = ? and status = 'CONFIRMED'",
                    request.targetSellerId(), request.sourceSellerId()
                );
            } else {
                String placeholders = String.join(",", java.util.Collections.nCopies(request.customerIds().size(), "?"));
                List<Object> params = new java.util.ArrayList<>();
                params.add(request.targetSellerId());
                params.add(request.sourceSellerId());
                params.addAll(request.customerIds());
                reassignedOrders = jdbc.update(
                    "update orders.orders set seller_id = ? where seller_id = ? and status = 'CONFIRMED' and customer_id in (" + placeholders + ")",
                    params.toArray()
                );
            }
        }

        audit.record(
            actorId(),
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

        return new SellerDtos.ReassignCustomersResponse(
            request.sourceSellerId(),
            request.targetSellerId(),
            reassignedCustomers,
            reassignedOrders
        );
    }

    @Transactional
    public SellerDtos.ReassignOrdersResponse reassignOrders(SellerDtos.ReassignOrdersRequest request) {
        if (request == null || request.targetSellerId() == null || request.orderIds() == null || request.orderIds().isEmpty()) {
            throw new IllegalArgumentException("targetSellerId y orderIds son obligatorios");
        }

        boolean targetExists = Boolean.TRUE.equals(
            jdbc.queryForObject("select exists(select 1 from seller.seller_profiles where id = ?)", Boolean.class, request.targetSellerId())
        );
        if (!targetExists) {
            throw new EmptyResultDataAccessException("El vendedor destino no existe", 1);
        }

        String targetStatus = jdbc.queryForObject("select status from seller.seller_profiles where id = ?", String.class, request.targetSellerId());
        if (!"ACTIVE".equalsIgnoreCase(targetStatus)) {
            throw new IllegalStateException("El vendedor destino debe estar activo");
        }

        boolean onlyPending = request.onlyPending() == null || Boolean.TRUE.equals(request.onlyPending());
        if (!onlyPending) {
            throw new IllegalArgumentException("Solo se pueden reasignar pedidos CONFIRMED");
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(request.orderIds().size(), "?"));
        List<Object> params = new java.util.ArrayList<>();
        params.add(request.targetSellerId());
        params.addAll(request.orderIds());

        String sql = "update orders.orders set seller_id = ? where id in (" + placeholders + ") and status = 'CONFIRMED'";

        int reassignedOrders = jdbc.update(sql, params.toArray());

        audit.record(
            actorId(),
            "SELLER_ORDER_REASSIGNMENT",
            "SELLER",
            request.targetSellerId().toString(),
            "SUCCESS",
            Map.of(
                "targetSellerId", request.targetSellerId().toString(),
                "reassignedOrdersCount", reassignedOrders
            )
        );

        return new SellerDtos.ReassignOrdersResponse(
            request.targetSellerId(),
            reassignedOrders
        );
    }

    private UUID actorId() {
        if (currentUser != null) {
            try {
                return currentUser.userId();
            } catch (Exception ignored) {
            }
        }
        return UUID.fromString("00000000-0000-0000-0000-000000000000");
    }
}
