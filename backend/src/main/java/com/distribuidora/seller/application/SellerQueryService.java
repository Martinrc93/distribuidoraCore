package com.distribuidora.seller.application;

import com.distribuidora.seller.application.SellerCommandService.SellerView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SellerQueryService {
    private final JdbcTemplate jdbc;

    public SellerQueryService(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public Optional<UUID> findSellerIdByUserId(UUID userId) {
        List<UUID> ids = jdbc.query(
            "select id from seller.seller_profiles where user_id = ?",
            (rs, i) -> rs.getObject(1, UUID.class),
            userId
        );
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids.getFirst());
    }

    public Optional<SellerView> findSellerById(UUID id) {
        List<SellerView> sellers = jdbc.query("""
            select sp.id, sp.user_id as "userId", sp.display_name as "displayName",
                   u.email, sp.status, sp.created_at as "createdAt",
                   (select count(*) from customer.customers c where c.seller_id = sp.id) as "assignedCustomersCount"
            from seller.seller_profiles sp
            join identity.users u on u.id = sp.user_id
            where sp.id = ?
            """,
            (rs, i) -> new SellerView(
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
}
