package com.distribuidora.identity.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.identity.api.UserAdminDtos;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class UserAdminService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;

    public UserAdminService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, AuditService audit,
                            CurrentUserAccess currentUser) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional
    public UUID create(UserAdminDtos.CreateUserRequest request) {
        if (request == null || request.role() == null || request.email() == null
            || request.email().isBlank() || request.temporaryPassword() == null
            || request.temporaryPassword().isBlank()) {
            throw new IllegalArgumentException("email, temporaryPassword y role son obligatorios");
        }
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (exists(email)) throw new IllegalStateException("El email ya está registrado");
        if (request.role() == UserAdminDtos.Role.SELLER
            && (request.displayName() == null || request.displayName().isBlank())) {
            throw new IllegalArgumentException("displayName es obligatorio para SELLER");
        }

        UUID userId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        try {
            jdbc.update("""
                insert into identity.users(id, email, password_hash, status, failed_login_attempts,
                    created_at, updated_at, version)
                values (?, ?, ?, 'ACTIVE', 0, ?, ?, 0)
                """, userId, email, passwordEncoder.encode(request.temporaryPassword()), now, now);
            UUID roleId = jdbc.queryForObject("select id from identity.roles where code = ?", UUID.class,
                request.role().name());
            jdbc.update("insert into identity.user_roles(user_id, role_id) values (?, ?)", userId, roleId);
            if (request.role() == UserAdminDtos.Role.SELLER) {
                jdbc.update("insert into seller.seller_profiles(id, user_id, display_name, status, created_at) values (?, ?, ?, 'ACTIVE', ?)",
                    UUID.randomUUID(), userId, request.displayName().trim(), now);
            }
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException("El email ya está registrado", exception);
        }
        audit.recordWithinTransaction(currentUser.userId(), "USER_CREATE", "USER", userId.toString(), "SUCCESS",
            Map.of("role", request.role().name(), "email", email));
        return userId;
    }

    private boolean exists(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from identity.users where lower(email) = lower(?))", Boolean.class, email));
    }
}
