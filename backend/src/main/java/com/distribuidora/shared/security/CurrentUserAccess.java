package com.distribuidora.shared.security;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Centralizes resource ownership checks so controllers cannot bypass seller scope. */
@Component
public class CurrentUserAccess {
    private final JdbcTemplate jdbc;

    public CurrentUserAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID userId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw new IllegalStateException("No se pudo identificar al usuario actual");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("La identidad autenticada no es válida", exception);
        }
    }

    public boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
            .anyMatch(authority -> "ADMIN_ALL".equals(authority.getAuthority()));
    }

    public Optional<UUID> sellerProfileId() {
        if (isAdmin()) return Optional.empty();
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                "select id from seller.seller_profiles where user_id = ? and status = 'ACTIVE'",
                UUID.class, userId()));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    public UUID requireSellerProfile() {
        return sellerProfileId().orElseThrow(() -> new EmptyResultDataAccessException(1));
    }

    public void requireCustomerAccess(UUID customerId) {
        if (isAdmin()) return;
        UUID sellerId = requireSellerProfile();
        boolean accessible = Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from customer.customers where id = ? and seller_id = ?)",
            Boolean.class, customerId, sellerId));
        if (!accessible) throw new EmptyResultDataAccessException(1);
    }

    public void requireOrderAccess(UUID orderId) {
        if (isAdmin()) return;
        UUID sellerId = requireSellerProfile();
        boolean accessible = Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(
                select 1
                from orders.orders o
                join customer.customers c on c.id = o.customer_id
                where o.id = ? and (o.seller_id = ? or (o.seller_id is null and c.seller_id = ?))
            )
            """, Boolean.class, orderId, sellerId, sellerId));
        if (!accessible) throw new EmptyResultDataAccessException(1);
    }
}
