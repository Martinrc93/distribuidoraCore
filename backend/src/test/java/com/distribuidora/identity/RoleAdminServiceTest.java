package com.distribuidora.identity;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.identity.application.RoleAdminService;
import com.distribuidora.identity.infrastructure.RefreshTokenRepository;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleAdminServiceTest {
    private static final String ADMIN_ROLE_LOCK = "select id from identity.roles where code = 'ADMIN' for update";
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final CurrentUserAccess currentUser = mock(CurrentUserAccess.class);
    private final RoleAdminService service = new RoleAdminService(jdbc, refreshTokens, audit, currentUser);
    private final UUID adminRoleId = UUID.randomUUID();
    private final UUID sellerRoleId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(jdbc.queryForObject(ADMIN_ROLE_LOCK, UUID.class)).thenReturn(adminRoleId);
        when(jdbc.queryForObject("select id from identity.roles where code = ? for update", UUID.class, "SELLER"))
            .thenReturn(sellerRoleId);
        when(jdbc.queryForList("select code from identity.permissions", String.class))
            .thenReturn(List.of("ADMIN_ALL", "USER_MANAGE", "ORDER_CREATE", "SALE_DELIVER", "STOCK_ADJUST", "ORDER_PREPARE", "ORDER_REVIEW"));
    }

    @Test
    void sellerCannotReceiveAdminAll() {
        assertThrows(IllegalArgumentException.class,
            () -> service.replacePermissions("seller", Set.of("ORDER_CREATE", "ADMIN_ALL")));

        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(refreshTokens, never()).revokeAllByUserId(any(), any());
        verify(audit, never()).recordWithinTransaction(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void createsAssignedRoleWithOnlyExplicitPermissionsAndAudit() {
        when(currentUser.isAdmin()).thenReturn(true);
        var role = service.create(" logistics ", " Logística ", Set.of("ORDER_PREPARE"));
        assertThat(role.code()).isEqualTo("LOGISTICS");
        assertThat(role.description()).isEqualTo("Logística");
        assertThat(role.permissions()).containsExactly("ORDER_PREPARE");
        assertThat(role.requiresSellerProfile()).isTrue();
        verify(jdbc).update(contains("insert into identity.roles"), eq(role.id()), eq("LOGISTICS"), eq("Logística"));
        verify(audit).recordWithinTransaction(any(), eq("ROLE_CREATE"), eq("ROLE"), eq("LOGISTICS"), eq("SUCCESS"), any());
        verify(refreshTokens, never()).revokeAllByUserId(any(), any());
    }

    @Test
    void customRolesRejectReservedCodesAndAdministrativeOrUnknownPermissions() {
        when(currentUser.isAdmin()).thenReturn(true);
        for (String code : List.of("ADMIN", "SELLER", "A", "BAD-CODE")) {
            assertThrows(IllegalArgumentException.class, () -> service.create(code, "Nombre", Set.of()));
        }
        for (String code : List.of("ADMIN_ALL", "USER_MANAGE", "ORDER_REVIEW", "UNKNOWN")) {
            assertThrows(IllegalArgumentException.class, () -> service.create("CUSTOM", "Nombre", Set.of(code)));
        }
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void customRoleCreationRequiresAdminAndTranslatesDuplicateCode() {
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.create("CUSTOM", "Nombre", Set.of()));
        when(currentUser.isAdmin()).thenReturn(true);
        when(jdbc.update(contains("insert into identity.roles"), any(), any(), any()))
            .thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));
        assertThrows(IllegalStateException.class, () -> service.create("CUSTOM", "Nombre", Set.of()));
        verify(audit, never()).recordWithinTransaction(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void sellerCannotReceiveUserManagement() {
        assertThrows(IllegalArgumentException.class,
            () -> service.replacePermissions("SELLER", Set.of("ORDER_CREATE", "USER_MANAGE")));

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void adminCannotLoseAdminAll() {
        when(jdbc.queryForObject("select id from identity.roles where code = ? for update", UUID.class, "ADMIN"))
            .thenReturn(adminRoleId);
        assertThrows(IllegalArgumentException.class,
            () -> service.replacePermissions("ADMIN", Set.of("USER_MANAGE", "STOCK_ADJUST")));

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void sellerCannotReceiveAdministrativeReview() {
        assertThrows(IllegalArgumentException.class,
            () -> service.replacePermissions("SELLER", Set.of("ORDER_PREPARE", "ORDER_REVIEW")));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void stalePermissionSnapshotCannotOverwriteAnotherAdministratorsChange() {
        when(jdbc.queryForList(contains("where rp.role_id = ? order by p.code"), eq(String.class), eq(sellerRoleId)))
            .thenReturn(List.of("ORDER_CREATE", "ORDER_PREPARE"));
        assertThrows(IllegalStateException.class,
            () -> service.replacePermissions("SELLER", Set.of("ORDER_CREATE", "SALE_DELIVER"), Set.of("ORDER_CREATE")));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(refreshTokens, never()).revokeAllByUserId(any(), any());
    }

    @Test
    void permissionMetadataUsesTheSameAdministrativeRestrictionsAsWrites() {
        assertThat(new RoleAdminService.PermissionView("ORDER_REVIEW", "Revisar pedidos").adminOnly()).isTrue();
        assertThat(new RoleAdminService.PermissionView("ORDER_PREPARE", "Preparar pedidos").adminOnly()).isFalse();
        assertThat(new RoleAdminService.RoleView(adminRoleId, "ADMIN", "Administrador", List.of("ADMIN_ALL")).requiredPermissions()).containsExactly("ADMIN_ALL");
    }
}
