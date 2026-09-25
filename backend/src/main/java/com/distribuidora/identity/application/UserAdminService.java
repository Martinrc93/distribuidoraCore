package com.distribuidora.identity.application;

import com.distribuidora.audit.application.AuditService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class UserAdminService {
    public interface CreateUserCommand {
        String email();
        String temporaryPassword();
        String roleCode();
        String displayName();
    }

    public interface InviteUserCommand {
        String email();
        String roleCode();
        String displayName();
    }

    public record InviteUserResult(UUID userId, String email, String activationToken, Instant expiresAt) { }

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
    public UUID create(CreateUserCommand request) {
        if (request == null || request.roleCode() == null || request.email() == null
            || request.email().isBlank() || request.temporaryPassword() == null
            || request.temporaryPassword().isBlank()) {
            throw new IllegalArgumentException("email, temporaryPassword y role son obligatorios");
        }
        if (!"ADMIN".equals(request.roleCode()) && !"SELLER".equals(request.roleCode())) {
            throw new IllegalArgumentException("role debe ser ADMIN o SELLER");
        }
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (exists(email)) throw new IllegalStateException("El email ya está registrado");
        if ("SELLER".equals(request.roleCode())
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
                request.roleCode());
            jdbc.update("insert into identity.user_roles(user_id, role_id) values (?, ?)", userId, roleId);
            if ("SELLER".equals(request.roleCode())) {
                jdbc.update("insert into seller.seller_profiles(id, user_id, display_name, status, created_at) values (?, ?, ?, 'ACTIVE', ?)",
                    UUID.randomUUID(), userId, request.displayName().trim(), now);
            }
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException("El email ya está registrado", exception);
        }
        audit.recordWithinTransaction(currentUser.userId(), "USER_CREATE", "USER", userId.toString(), "SUCCESS",
            Map.of("role", request.roleCode(), "email", email));
        return userId;
    }

    @Transactional
    public InviteUserResult invite(InviteUserCommand request) {
        if (request == null || request.roleCode() == null || request.email() == null || request.email().isBlank()) {
            throw new IllegalArgumentException("email y role son obligatorios");
        }
        if (!"ADMIN".equals(request.roleCode()) && !"SELLER".equals(request.roleCode())) {
            throw new IllegalArgumentException("role debe ser ADMIN o SELLER");
        }
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (exists(email)) {
            throw new IllegalStateException("El email ya está registrado");
        }
        if ("SELLER".equals(request.roleCode())
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
                request.roleCode());
            jdbc.update("insert into identity.user_roles(user_id, role_id) values (?, ?)", userId, roleId);

            if ("SELLER".equals(request.roleCode())) {
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
            Map.of("role", request.roleCode(), "email", email));

        return new InviteUserResult(userId, email, rawToken, expiresAt);
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
        jdbc.queryForObject("select id from identity.roles where code = 'ADMIN' for update", UUID.class);
        String status = jdbc.queryForObject("select status from identity.users where id = ? for update",
            String.class, userId);
        if ("ACTIVE".equals(status) && userHasAdminRole(userId)) {
            ensureAnotherActiveAdmin(userId);
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

    @Transactional
    public void changeRole(UUID userId, String roleCode) {
        if (userId == null || roleCode == null) throw new IllegalArgumentException("userId y role son obligatorios");
        if (!"ADMIN".equals(roleCode) && !"SELLER".equals(roleCode)) {
            throw new IllegalArgumentException("role debe ser ADMIN o SELLER");
        }
        jdbc.queryForObject("select id from identity.roles where code = 'ADMIN' for update", UUID.class);
        String status = jdbc.queryForObject("select status from identity.users where id = ? for update",
            String.class, userId);
        if (!"ACTIVE".equals(status)) throw new IllegalStateException("Solo se puede cambiar el rol de usuarios activos");

        List<String> oldRoles = jdbc.queryForList("""
            select r.code from identity.user_roles ur join identity.roles r on r.id = ur.role_id
            where ur.user_id = ? order by r.code
            """, String.class, userId);
        String targetRole = roleCode;
        if (oldRoles.size() == 1 && oldRoles.contains(targetRole)) return;

        List<String> sellerStatuses = jdbc.queryForList(
            "select status from seller.seller_profiles where user_id = ?", String.class, userId);
        String sellerStatus = sellerStatuses.isEmpty() ? null : sellerStatuses.getFirst();
        if ("SELLER".equals(roleCode) && !"ACTIVE".equals(sellerStatus)) {
            throw new IllegalStateException("Para asignar SELLER se requiere un perfil de vendedor activo");
        }
        if ("ADMIN".equals(roleCode) && "ACTIVE".equals(sellerStatus)) {
            throw new IllegalStateException("Desactive primero el perfil de vendedor antes de cambiar a ADMIN");
        }

        if (oldRoles.contains("ADMIN") && !"ADMIN".equals(roleCode)) {
            ensureAnotherActiveAdmin(userId);
        }

        UUID targetRoleId = jdbc.queryForObject("select id from identity.roles where code = ?", UUID.class, targetRole);
        jdbc.update("delete from identity.user_roles where user_id = ?", userId);
        jdbc.update("insert into identity.user_roles(user_id, role_id) values (?, ?)", userId, targetRoleId);
        Instant now = Instant.now();
        jdbc.update("update identity.users set version = version + 1, updated_at = ? where id = ?",
            Timestamp.from(now), userId);
        refreshTokens.revokeAllByUserId(userId, now);
        audit.recordWithinTransaction(currentUser.userId(), "USER_ROLE_CHANGE", "USER", userId.toString(), "SUCCESS",
            Map.of("fromRoles", String.join(",", oldRoles), "toRole", targetRole));
    }

    private boolean exists(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from identity.users where lower(email) = lower(?))", Boolean.class, email));
    }

    private boolean userExists(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "select exists(select 1 from identity.users where id = ?)", Boolean.class, userId));
    }

    private boolean userHasAdminRole(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists(
                select 1 from identity.user_roles ur
                join identity.roles r on r.id = ur.role_id and r.code = 'ADMIN'
                join identity.role_permissions rp on rp.role_id = r.id
                join identity.permissions p on p.id = rp.permission_id and p.code = 'ADMIN_ALL'
                where ur.user_id = ?
            )
            """, Boolean.class, userId));
    }

    private void ensureAnotherActiveAdmin(UUID excludedUserId) {
        Long otherAdmins = jdbc.queryForObject("""
            select count(distinct u.id)
            from identity.users u
            join identity.user_roles ur on ur.user_id = u.id
            join identity.roles r on r.id = ur.role_id and r.code = 'ADMIN'
            join identity.role_permissions rp on rp.role_id = r.id
            join identity.permissions p on p.id = rp.permission_id and p.code = 'ADMIN_ALL'
            where u.status = 'ACTIVE' and u.id <> ?
            """, Long.class, excludedUserId);
        if (otherAdmins == null || otherAdmins == 0) {
            throw new IllegalStateException("No se puede quitar el acceso del último administrador activo");
        }
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

}
