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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
            .thenReturn(List.of("ADMIN_ALL", "USER_MANAGE", "ORDER_CREATE", "SALE_DELIVER", "STOCK_ADJUST"));
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
}
