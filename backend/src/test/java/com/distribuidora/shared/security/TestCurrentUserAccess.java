package com.distribuidora.shared.security;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

/** Admin test fixture for unit and integration tests constructing services directly. */
public final class TestCurrentUserAccess extends CurrentUserAccess {
    private TestCurrentUserAccess(JdbcTemplate jdbc) {
        super(jdbc);
    }

    public static CurrentUserAccess admin(JdbcTemplate jdbc) {
        return new TestCurrentUserAccess(jdbc);
    }

    @Override
    public UUID userId() {
        return resolveActorIdOrSystem();
    }

    @Override
    public boolean isAdmin() {
        return true;
    }

    @Override
    public Optional<UUID> sellerProfileId() {
        return Optional.empty();
    }

    @Override
    public void requireCustomerAccess(UUID customerId) {
    }

    @Override
    public void requireOrderAccess(UUID orderId) {
    }

    @Override
    public void requireOrderCustomerAccess(UUID orderId, UUID customerId) {
    }
}
