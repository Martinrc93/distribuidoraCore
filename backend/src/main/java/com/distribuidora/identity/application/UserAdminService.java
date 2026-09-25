package com.distribuidora.identity.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.identity.api.UserAdminDtos;
import com.distribuidora.identity.domain.UserActivationToken;
import com.distribuidora.identity.infrastructure.RefreshTokenRepository;
import com.distribuidora.identity.infrastructure.UserActivationTokenRepository;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
public class UserAdminService {
    private final JdbcTemplate jdbc;
    private final UserActivationTokenRepository activationTokens;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;
    private final SecureRandom secureRandom = new SecureRandom();

    public UserAdminService(JdbcTemplate jdbc,
                            UserActivationTokenRepository activationTokens,
                            RefreshTokenRepository refreshTokens,
                            PasswordEncoder passwordEncoder,
                            AuditService audit,
                            CurrentUserAccess currentUser) {
        this.jdbc = jdbc;
        this.activationTokens = activationTokens;
        this.refreshTokens = refreshTokens;
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

    @Transactional
    public UserAdminDtos.InviteUserResponse invite(UserAdminDtos.InviteUserRequest request) {
        if (request == null || request.role() == null || request.email() == null || request.email().isBlank()) {
            throw new IllegalArgumentException("email y role son obligatorios");
        }
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (exists(email)) {
            throw new IllegalStateException("El email ya está registrado");
        }
        if (request.role() == UserAdminDtos.Role.SELLER
            && (request.displayName() == null || request.displayName().isBlank())) {
            throw new IllegalArgumentException("displayName es obligatorio para SELLER");
        }

        UUID userId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        String unmatchableHash = "!INVITED!" + UUID.randomUUID();

        try {
            jdbc.update("""
                insert into identity.users(id, email, password_hash, status, failed_login_attempts,
                    created_at, updated_at, version)
                values (?, ?, ?, 'INVITED', 0, ?, ?, 0)
                """, userId, email, unmatchableHash, now, now);

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

        String rawToken = generateRawToken();
        String tokenHash = AuthService.hashToken(rawToken);
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(30));

        UserActivationToken token = new UserActivationToken(userId, tokenHash, expiresAt);
        activationTokens.save(token);

        audit.recordWithinTransaction(currentUser.userId(), "USER_INVITE", "USER", userId.toString(), "SUCCESS",
            Map.of("role", request.role().name(), "email", email));

        return new UserAdminDtos.InviteUserResponse(userId, email, rawToken, expiresAt);
    }

    @Transactional
    public void revokeSessions(UUID userId) {
        if (!userExists(userId)) {
            throw new IllegalArgumentException("Usuario no encontrado");
        }
        jdbc.update("update identity.users set version = version + 1, updated_at = ? where id = ?",
            Timestamp.from(Instant.now()), userId);
        refreshTokens.revokeAllByUserId(userId, Instant.now());
        audit.record(currentUser.userId(), "REVOKE_SESSIONS", "USER", userId.toString(), "SUCCESS", Map.of());
    }

    @Transactional
    public void blockUser(UUID userId) {
        if (!userExists(userId)) {
            throw new IllegalArgumentException("Usuario no encontrado");
        }
        jdbc.update("update identity.users set status = 'BLOCKED', version = version + 1, updated_at = ? where id = ?",
            Timestamp.from(Instant.now()), userId);
        refreshTokens.revokeAllByUserId(userId, Instant.now());
        audit.record(currentUser.userId(), "USER_BLOCK", "USER", userId.toString(), "SUCCESS", Map.of());
    }

    @Transactional
    public void unblockUser(UUID userId) {
        if (!userExists(userId)) {
            throw new IllegalArgumentException("Usuario no encontrado");
        }
        jdbc.update("update identity.users set status = 'ACTIVE', failed_login_attempts = 0, locked_until = null, version = version + 1, updated_at = ? where id = ?",
            Timestamp.from(Instant.now()), userId);
        audit.record(currentUser.userId(), "USER_UNBLOCK", "USER", userId.toString(), "SUCCESS", Map.of());
    }

    private boolean exists(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from identity.users where lower(email) = lower(?))", Boolean.class, email));
    }

    private boolean userExists(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from identity.users where id = ?)", Boolean.class, userId));
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
