package com.distribuidora.identity.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.identity.infrastructure.RefreshTokenRepository;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
    public record RoleView(UUID id, String code, String description, List<String> permissions) { }
    public record PermissionView(String code, String description) { }

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
            select r.id, r.code, r.description, p.code as permission_code
            from identity.roles r
            left join identity.role_permissions rp on rp.role_id = r.id
            left join identity.permissions p on p.id = rp.permission_id
            order by r.code, p.code
            """, (result, rowNumber) -> new RolePermissionRow(
            result.getObject("id", UUID.class), result.getString("code"), result.getString("description"),
            result.getString("permission_code")));
        Map<UUID, RoleBuilder> roles = new LinkedHashMap<>();
        for (RolePermissionRow row : rows) {
            RoleBuilder role = roles.computeIfAbsent(row.id(), ignored ->
                new RoleBuilder(row.id(), row.code(), row.description()));
            if (row.permissionCode() != null) role.permissions().add(row.permissionCode());
        }
        return roles.values().stream().map(role -> new RoleView(
            role.id(), role.code(), role.description(), List.copyOf(role.permissions())
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
            && (requested.contains("ADMIN_ALL") || requested.contains("USER_MANAGE"))) {
            throw new IllegalArgumentException("ADMIN_ALL y USER_MANAGE solo se pueden asignar al rol ADMIN");
        }

        List<String> current = jdbc.queryForList("""
            select p.code from identity.role_permissions rp
            join identity.permissions p on p.id = rp.permission_id
            where rp.role_id = ? order by p.code
            """, String.class, roleId);
        if (new TreeSet<>(current).equals(requested)) return roleByCode(normalizedRole);

        if ("ADMIN".equals(normalizedRole) && !requested.contains("ADMIN_ALL")) {
            throw new IllegalArgumentException("No se puede retirar el último permiso administrativo");
        }

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
            jdbc.update("update identity.users set version = version + 1, updated_at = ? where id = ?",
                Timestamp.from(now), userId);
            refreshTokens.revokeAllByUserId(userId, now);
        }
        audit.recordWithinTransaction(currentUser.userId(), "ROLE_PERMISSIONS_UPDATE", "ROLE", normalizedRole,
            "SUCCESS", Map.of("permissions", String.join(",", requested)));
        return roleByCode(normalizedRole);
    }

    private RoleView roleByCode(String code) {
        return listRoles().stream().filter(role -> role.code().equals(code)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Rol no encontrado"));
    }

    private String normalizeCode(String code, String field) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException(field + " es obligatorio");
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private record RolePermissionRow(UUID id, String code, String description, String permissionCode) { }

    private static final class RoleBuilder {
        private final UUID id;
        private final String code;
        private final String description;
        private final Set<String> permissions = new LinkedHashSet<>();

        private RoleBuilder(UUID id, String code, String description) {
            this.id = id;
            this.code = code;
            this.description = description;
        }

        UUID id() { return id; }
        String code() { return code; }
        String description() { return description; }
        Set<String> permissions() { return permissions; }
    }
}
