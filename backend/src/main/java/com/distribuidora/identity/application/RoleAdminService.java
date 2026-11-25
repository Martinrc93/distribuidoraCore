package com.distribuidora.identity.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.identity.infrastructure.RefreshTokenRepository;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class RoleAdminService {
    private static final Set<String> ADMIN_ONLY_PERMISSIONS = Set.of(
        "ADMIN_ALL", "USER_MANAGE", "ORDER_REVIEW", "STOCK_ADJUST", "STOCK_SHRINKAGE", "RESELLER_MANAGE");

    public record RoleView(UUID id, String code, String description, List<String> permissions,
                           List<String> requiredPermissions, boolean assignedToCurrentUser) {
        public boolean requiresSellerProfile() { return !"ADMIN".equals(code); }
        public RoleView(UUID id, String code, String description, List<String> permissions) {
            this(id, code, description, permissions, requiredPermissionsFor(code), false);
        }
    }
    public record PermissionView(String code, String description, boolean adminOnly) {
        public PermissionView(String code, String description) {
            this(code, description, ADMIN_ONLY_PERMISSIONS.contains(code));
        }
    }
    public record PermissionUpdateResult(RoleView role, boolean currentSessionRevoked) { }

    @Transactional
    public RoleView create(String code, String description, Set<String> permissionCodes) {
        if (!currentUser.isAdmin()) throw new org.springframework.security.access.AccessDeniedException("Solo administradores pueden crear roles");
        String normalized = normalizeCode(code, "code");
        if (!normalized.matches("[A-Z][A-Z0-9_]{1,79}") || Set.of("ADMIN", "SELLER").contains(normalized)) {
            throw new IllegalArgumentException("El código debe tener de 2 a 80 letras, números o guiones bajos y no puede ser ADMIN ni SELLER");
        }
        if (description == null || description.isBlank() || description.trim().length() > 255) {
            throw new IllegalArgumentException("El nombre del rol es obligatorio y admite hasta 255 caracteres");
        }
        if (permissionCodes == null) throw new IllegalArgumentException("permissionCodes es obligatorio");
        Set<String> requested = new TreeSet<>();
        for (String permission : permissionCodes) requested.add(normalizeCode(permission, "permissionCode"));
        if (requested.stream().anyMatch(ADMIN_ONLY_PERMISSIONS::contains)) {
            throw new IllegalArgumentException("Los roles personalizados no admiten permisos reservados a administradores");
        }
        Set<String> unknown = new TreeSet<>(requested);
        unknown.removeAll(jdbc.queryForList("select code from identity.permissions", String.class));
        if (!unknown.isEmpty()) throw new IllegalArgumentException("Permisos desconocidos: " + String.join(", ", unknown));
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into identity.roles(id, code, description) values (?, ?, ?)", id, normalized, description.trim());
        } catch (DuplicateKeyException exception) {
            throw new IllegalStateException("Ya existe un rol con ese código", exception);
        }
        for (String permission : requested) {
            jdbc.update("insert into identity.role_permissions(role_id, permission_id) select ?, id from identity.permissions where code = ?", id, permission);
        }
        audit.recordWithinTransaction(currentUser.userId(), "ROLE_CREATE", "ROLE", normalized, "SUCCESS",
            Map.of("permissions", String.join(",", requested), "scope", "ASSIGNED"));
        return new RoleView(id, normalized, description.trim(), List.copyOf(requested));
    }

    private final JdbcTemplate jdbc;
    private final RefreshTokenRepository refreshTokens;
    private final AuditService audit;
    private final CurrentUserAccess currentUser;

    public RoleAdminService(JdbcTemplate jdbc, RefreshTokenRepository refreshTokens,
                            AuditService audit, CurrentUserAccess currentUser) {
        this.jdbc = jdbc;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<RoleView> listRoles() {
        List<RolePermissionRow> rows = jdbc.query("""
            select r.id, r.code, r.description, p.code as permission_code,
                   exists(select 1 from identity.user_roles ur where ur.role_id = r.id and ur.user_id = ?) as assigned_to_current_user
            from identity.roles r
            left join identity.role_permissions rp on rp.role_id = r.id
            left join identity.permissions p on p.id = rp.permission_id
            order by r.code, p.code
            """, (result, rowNumber) -> new RolePermissionRow(
            result.getObject("id", UUID.class), result.getString("code"), result.getString("description"),
            result.getString("permission_code"), result.getBoolean("assigned_to_current_user")), currentUser.userId());
        Map<UUID, RoleBuilder> roles = new LinkedHashMap<>();
        for (RolePermissionRow row : rows) {
            RoleBuilder role = roles.computeIfAbsent(row.id(), ignored ->
                new RoleBuilder(row.id(), row.code(), row.description(), row.assignedToCurrentUser()));
            if (row.permissionCode() != null) role.permissions().add(row.permissionCode());
        }
        return roles.values().stream().map(role -> new RoleView(
            role.id(), role.code(), role.description(), List.copyOf(role.permissions()),
            requiredPermissionsFor(role.code()), role.assignedToCurrentUser()
        )).toList();
    }

    @Transactional(readOnly = true)
    public List<PermissionView> listPermissions() {
        return jdbc.query("select code, description from identity.permissions order by code",
            (result, rowNumber) -> new PermissionView(
                result.getString("code"), result.getString("description")));
    }

    @Transactional
    public RoleView replacePermissions(String roleCode, Set<String> permissionCodes) {
        return replacePermissions(roleCode, permissionCodes, null).role();
    }

    @Transactional
    public PermissionUpdateResult replacePermissions(String roleCode, Set<String> permissionCodes,
                                                     Set<String> expectedPermissionCodes) {
        String normalizedRole = normalizeCode(roleCode, "roleCode");
        if (permissionCodes == null) throw new IllegalArgumentException("permissionCodes es obligatorio");
        Set<String> requested = new TreeSet<>();
        for (String permissionCode : permissionCodes) requested.add(normalizeCode(permissionCode, "permissionCode"));

        // Serializa cambios sensibles de roles para no permitir eliminar el último ADMIN concurrentemente.
        jdbc.queryForObject("select id from identity.roles where code = 'ADMIN' for update", UUID.class);
        UUID roleId = jdbc.queryForObject("select id from identity.roles where code = ? for update",
            UUID.class, normalizedRole);
        List<String> knownCodes = jdbc.queryForList("select code from identity.permissions", String.class);
        Set<String> unknown = new TreeSet<>(requested);
        unknown.removeAll(knownCodes);
        if (!unknown.isEmpty()) throw new IllegalArgumentException("Permisos desconocidos: " + String.join(", ", unknown));

        if ("ADMIN".equals(normalizedRole) && !requested.contains("ADMIN_ALL")) {
            throw new IllegalArgumentException("El rol ADMIN debe conservar ADMIN_ALL");
        }
        if (!"ADMIN".equals(normalizedRole)
            && requested.stream().anyMatch(ADMIN_ONLY_PERMISSIONS::contains)) {
            throw new IllegalArgumentException("Los permisos reservados a administradores solo se pueden asignar al rol ADMIN");
        }

        List<String> current = jdbc.queryForList("""
            select p.code from identity.role_permissions rp
            join identity.permissions p on p.id = rp.permission_id
            where rp.role_id = ? order by p.code
            """, String.class, roleId);
        if (expectedPermissionCodes != null) {
            Set<String> expected = new TreeSet<>();
            for (String code : expectedPermissionCodes) expected.add(normalizeCode(code, "expectedPermissionCode"));
            if (!new TreeSet<>(current).equals(expected)) {
                throw new IllegalStateException("Los permisos de este rol cambiaron. Cerrá la edición y recargá los roles antes de volver a guardar.");
            }
        }
        if (new TreeSet<>(current).equals(requested)) return new PermissionUpdateResult(roleByCode(normalizedRole), false);

        jdbc.update("delete from identity.role_permissions where role_id = ?", roleId);
        for (String code : requested) {
            jdbc.update("""
                insert into identity.role_permissions(role_id, permission_id)
                select ?, id from identity.permissions where code = ?
                """, roleId, code);
        }
        Instant now = Instant.now();
        List<UUID> affectedUsers = jdbc.queryForList(
            "select user_id from identity.user_roles where role_id = ?", UUID.class, roleId);
        for (UUID userId : affectedUsers) {
            jdbc.update("update identity.users set session_version = session_version + 1, version = version + 1, updated_at = ? where id = ?",
                Timestamp.from(now), userId);
            refreshTokens.revokeAllByUserId(userId, now);
        }
        audit.recordWithinTransaction(currentUser.userId(), "ROLE_PERMISSIONS_UPDATE", "ROLE", normalizedRole,
            "SUCCESS", Map.of("permissions", String.join(",", requested)));
        return new PermissionUpdateResult(roleByCode(normalizedRole), affectedUsers.contains(currentUser.userId()));
    }

    private RoleView roleByCode(String code) {
        return listRoles().stream().filter(role -> role.code().equals(code)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Rol no encontrado"));
    }

    private String normalizeCode(String code, String field) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException(field + " es obligatorio");
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static List<String> requiredPermissionsFor(String roleCode) {
        return "ADMIN".equals(roleCode) ? List.of("ADMIN_ALL") : List.of();
    }

    private record RolePermissionRow(UUID id, String code, String description, String permissionCode,
                                     boolean assignedToCurrentUser) { }

    private static final class RoleBuilder {
        private final UUID id;
        private final String code;
        private final String description;
        private final Set<String> permissions = new LinkedHashSet<>();
        private final boolean assignedToCurrentUser;

        private RoleBuilder(UUID id, String code, String description, boolean assignedToCurrentUser) {
            this.id = id;
            this.code = code;
            this.description = description;
            this.assignedToCurrentUser = assignedToCurrentUser;
        }

        UUID id() { return id; }
        String code() { return code; }
        String description() { return description; }
        Set<String> permissions() { return permissions; }
        boolean assignedToCurrentUser() { return assignedToCurrentUser; }
    }
}
