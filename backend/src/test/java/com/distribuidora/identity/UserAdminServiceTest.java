package com.distribuidora.identity;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.identity.api.UserAdminDtos;
import com.distribuidora.identity.application.UserAdminService;
import com.distribuidora.identity.domain.UserActivationToken;
import com.distribuidora.identity.infrastructure.RefreshTokenRepository;
import com.distribuidora.identity.infrastructure.UserActivationTokenRepository;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

    @Mock JdbcTemplate jdbc;
    @Mock UserActivationTokenRepository activationTokens;
    @Mock RefreshTokenRepository refreshTokens;
    @Mock AuditService audit;
    @Mock CurrentUserAccess currentUser;

    UserAdminService service;
    Argon2PasswordEncoder encoder;
    UUID currentAdminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        org.mockito.Mockito.lenient().when(currentUser.userId()).thenReturn(currentAdminId);
        service = new UserAdminService(jdbc, activationTokens, refreshTokens, encoder, audit, currentUser);
    }

    @Test
    void inviteCreatesInvitedUserAndReturnsActivationToken() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("seller@distribuidora.local")))
            .thenReturn(false);
        when(jdbc.queryForObject(anyString(), eq(UUID.class), eq("SELLER")))
            .thenReturn(UUID.randomUUID());

        UserAdminDtos.InviteUserRequest request = new UserAdminDtos.InviteUserRequest(
            "seller@distribuidora.local",
            UserAdminDtos.Role.SELLER,
            "Vendedor Uno"
        );

        UserAdminDtos.InviteUserResponse response = service.invite(request);

        assertThat(response.userId()).isNotNull();
        assertThat(response.email()).isEqualTo("seller@distribuidora.local");
        assertThat(response.activationToken()).isNotBlank();
        assertThat(response.expiresAt()).isAfter(Instant.now());

        // Verify activation token saved in repository
        ArgumentCaptor<UserActivationToken> tokenCaptor = ArgumentCaptor.forClass(UserActivationToken.class);
        verify(activationTokens).save(tokenCaptor.capture());
        UserActivationToken savedToken = tokenCaptor.getValue();
        assertThat(savedToken.getUserId()).isEqualTo(response.userId());
        assertThat(savedToken.getTokenHash()).isNotBlank();
        assertThat(savedToken.getExpiresAt()).isAfter(Instant.now());

        // Verify audit
        verify(audit).recordWithinTransaction(eq(currentAdminId), eq("USER_INVITE"), eq("USER"),
            eq(response.userId().toString()), eq("SUCCESS"), any());
    }

    @Test
    void inviteDuplicateEmailThrowsException() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq("existing@distribuidora.local")))
            .thenReturn(true);

        UserAdminDtos.InviteUserRequest request = new UserAdminDtos.InviteUserRequest(
            "existing@distribuidora.local",
            UserAdminDtos.Role.ADMIN,
            null
        );

        assertThatThrownBy(() -> service.invite(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("registrado");
    }

    @Test
    void revokeSessionsInvalidatesUserRefreshTokens() {
        UUID targetUserId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(targetUserId)))
            .thenReturn(true);

        service.revokeSessions(targetUserId);

        ArgumentCaptor<String> updateSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(updateSql.capture(), any(), eq(targetUserId));
        org.assertj.core.api.Assertions.assertThat(updateSql.getValue()).contains("version = version + 1");
        verify(refreshTokens).revokeAllByUserId(eq(targetUserId), any());
        verify(audit).record(eq(currentAdminId), eq("REVOKE_SESSIONS"), eq("USER"),
            eq(targetUserId.toString()), eq("SUCCESS"), any());
    }

    @Test
    void blockUserUpdatesStatusAndRevokesSessions() {
        UUID targetUserId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(targetUserId)))
            .thenReturn(true);

        service.blockUser(targetUserId);

        ArgumentCaptor<String> updateSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(updateSql.capture(), any(Object[].class));
        assertThat(updateSql.getValue()).contains("status = 'BLOCKED'", "version = version + 1");
        verify(refreshTokens).revokeAllByUserId(eq(targetUserId), any());
        verify(audit).record(eq(currentAdminId), eq("USER_BLOCK"), eq("USER"),
            eq(targetUserId.toString()), eq("SUCCESS"), any());
    }

    @Test
    void unblockUserUpdatesStatusAndClearsLock() {
        UUID targetUserId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(targetUserId)))
            .thenReturn(true);

        service.unblockUser(targetUserId);

        ArgumentCaptor<String> updateSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(updateSql.capture(), any(Object[].class));
        assertThat(updateSql.getValue()).contains("status = 'ACTIVE'", "version = version + 1");
        verify(audit).record(eq(currentAdminId), eq("USER_UNBLOCK"), eq("USER"),
            eq(targetUserId.toString()), eq("SUCCESS"), any());
    }
}
