package com.distribuidora.identity;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.identity.api.AuthDtos;
import com.distribuidora.identity.application.AuthService;
import com.distribuidora.identity.application.InvalidRefreshTokenException;
import com.distribuidora.identity.domain.RefreshToken;
import com.distribuidora.identity.domain.UserAccount;
import com.distribuidora.identity.domain.UserStatus;
import com.distribuidora.identity.infrastructure.RefreshTokenRepository;
import com.distribuidora.identity.infrastructure.UserAccountRepository;
import com.distribuidora.shared.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserAccountRepository users;
    @Mock RefreshTokenRepository refreshTokens;
    @Mock JwtService jwtService;
    @Mock AuditService auditService;

    Argon2PasswordEncoder encoder;
    AuthService authService;

    @BeforeEach
    void setUp() {
        encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        authService = new AuthService(users, refreshTokens, encoder, jwtService, auditService, 7);
    }

    @Test
    void loginIssuesAccessTokenAndRefreshToken() {
        UserAccount user = new UserAccount("admin@distribuidora.local", encoder.encode("Correct123"));
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", userId);

        when(users.findByEmailIgnoreCase("admin@distribuidora.local")).thenReturn(Optional.of(user));
        when(users.findAuthorityCodes(userId)).thenReturn(List.of("ADMIN_ALL", "ORDER_CREATE", "STOCK_ADJUST"));
        when(jwtService.issue(user, List.of("ADMIN_ALL", "ORDER_CREATE", "STOCK_ADJUST"))).thenReturn("access-token-123");
        when(jwtService.accessTokenSeconds()).thenReturn(900L);

        AuthDtos.LoginResponse response = authService.login(new AuthDtos.LoginRequest("ADMIN@DISTRIBUIDORA.LOCAL", "Correct123"));

        assertThat(response.accessToken()).isEqualTo("access-token-123");
        assertThat(response.expiresInSeconds()).isEqualTo(900L);
        assertThat(response.refreshToken()).isNotBlank();

        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).save(tokenCaptor.capture());
        RefreshToken savedToken = tokenCaptor.getValue();
        assertThat(savedToken.getUserId()).isEqualTo(userId);
        assertThat(savedToken.getTokenHash()).isNotBlank();
        assertThat(savedToken.getExpiresAt()).isAfter(Instant.now());
        assertThat(savedToken.getRevokedAt()).isNull();

        verify(auditService).record(eq(userId), eq("LOGIN"), eq("USER"), eq(userId.toString()), eq("SUCCESS"), any());
    }

    @Test
    void invalidPasswordIsRejected() {
        UserAccount user = new UserAccount("admin@distribuidora.local", encoder.encode("Correct123"));
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", userId);
        when(users.findByEmailIgnoreCase("admin@distribuidora.local")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new AuthDtos.LoginRequest("admin@distribuidora.local", "wrong")))
            .isInstanceOf(BadCredentialsException.class);

        verify(refreshTokens, never()).save(any());
        verify(auditService).record(eq(userId), eq("LOGIN"), eq("USER"), eq(userId.toString()), eq("FAILURE"), any());
    }

    @Test
    void refreshRotatesRefreshTokenAndIssuesNewAccessToken() {
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount("seller@distribuidora.local", encoder.encode("Correct123"));
        ReflectionTestUtils.setField(user, "id", userId);

        String rawToken = "my-valid-refresh-token";
        String tokenHash = AuthService.hashToken(rawToken);

        RefreshToken oldToken = new RefreshToken(userId, tokenHash, Instant.now().plusSeconds(86400));
        UUID oldTokenId = UUID.randomUUID();
        ReflectionTestUtils.setField(oldToken, "id", oldTokenId);

        when(refreshTokens.findByTokenHash(tokenHash)).thenReturn(Optional.of(oldToken));
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(users.findAuthorityCodes(userId)).thenReturn(List.of("ORDER_CREATE"));
        when(jwtService.issue(user, List.of("ORDER_CREATE"))).thenReturn("new-access-token");
        when(jwtService.accessTokenSeconds()).thenReturn(900L);

        AuthDtos.LoginResponse response = authService.refresh(new AuthDtos.RefreshRequest(rawToken));

        assertThat(response.accessToken()).isEqualTo("new-access-token");
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotEqualTo(rawToken);

        // Old token should be marked revoked with replacedBy
        assertThat(oldToken.isRevoked()).isTrue();
        assertThat(oldToken.getReplacedBy()).isNotNull();

        // New token and old token should be saved
        ArgumentCaptor<RefreshToken> savedCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens, org.mockito.Mockito.times(2)).save(savedCaptor.capture());
        List<RefreshToken> savedTokens = savedCaptor.getAllValues();
        RefreshToken newToken = savedTokens.get(0);
        RefreshToken savedOldToken = savedTokens.get(1);

        assertThat(newToken.getUserId()).isEqualTo(userId);
        assertThat(newToken.getExpiresAt()).isAfter(Instant.now());
        assertThat(savedOldToken.getId()).isEqualTo(oldTokenId);
        assertThat(savedOldToken.getReplacedBy()).isEqualTo(newToken.getId());
        assertThat(savedOldToken.isRevoked()).isTrue();

        verify(auditService).record(eq(userId), eq("TOKEN_REFRESH"), eq("USER"), eq(userId.toString()), eq("SUCCESS"), any());
    }

    @Test
    void refreshWithUnknownTokenThrowsException() {
        String rawToken = "unknown-token";
        String tokenHash = AuthService.hashToken(rawToken);
        when(refreshTokens.findByTokenHash(tokenHash)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new AuthDtos.RefreshRequest(rawToken)))
            .isInstanceOf(InvalidRefreshTokenException.class)
            .hasMessageContaining("inválido");

        verify(auditService).record(eq(null), eq("TOKEN_REFRESH"), eq("USER"), eq(tokenHash), eq("FAILURE"), any());
    }

    @Test
    void refreshWithExpiredTokenThrowsException() {
        UUID userId = UUID.randomUUID();
        String rawToken = "expired-token";
        String tokenHash = AuthService.hashToken(rawToken);

        RefreshToken expiredToken = new RefreshToken(userId, tokenHash, Instant.now().minusSeconds(100));
        when(refreshTokens.findByTokenHash(tokenHash)).thenReturn(Optional.of(expiredToken));

        assertThatThrownBy(() -> authService.refresh(new AuthDtos.RefreshRequest(rawToken)))
            .isInstanceOf(InvalidRefreshTokenException.class)
            .hasMessageContaining("expirado");

        verify(auditService).record(eq(userId), eq("TOKEN_REFRESH"), eq("USER"), eq(userId.toString()), eq("FAILURE"), any());
    }

    @Test
    void refreshWithRevokedTokenTriggersReuseDetectionAndRevokesAllSessions() {
        UUID userId = UUID.randomUUID();
        String rawToken = "already-revoked-token";
        String tokenHash = AuthService.hashToken(rawToken);

        RefreshToken revokedToken = new RefreshToken(userId, tokenHash, Instant.now().plusSeconds(86400));
        revokedToken.revoke(Instant.now().minusSeconds(300));

        when(refreshTokens.findByTokenHash(tokenHash)).thenReturn(Optional.of(revokedToken));

        assertThatThrownBy(() -> authService.refresh(new AuthDtos.RefreshRequest(rawToken)))
            .isInstanceOf(InvalidRefreshTokenException.class)
            .hasMessageContaining("revocado");

        // Should invalidate all tokens for that user
        verify(refreshTokens).revokeAllByUserId(eq(userId), any());
        verify(auditService).record(eq(userId), eq("TOKEN_REUSE_DETECTED"), eq("USER"), eq(userId.toString()), eq("FAILURE"), any());
    }

    @Test
    void logoutRevokesRefreshToken() {
        UUID userId = UUID.randomUUID();
        String rawToken = "token-to-logout";
        String tokenHash = AuthService.hashToken(rawToken);

        RefreshToken activeToken = new RefreshToken(userId, tokenHash, Instant.now().plusSeconds(86400));
        when(refreshTokens.findByTokenHash(tokenHash)).thenReturn(Optional.of(activeToken));

        authService.logout(new AuthDtos.LogoutRequest(rawToken));

        assertThat(activeToken.isRevoked()).isTrue();
        verify(auditService).record(eq(userId), eq("LOGOUT"), eq("USER"), eq(userId.toString()), eq("SUCCESS"), any());
    }
}
