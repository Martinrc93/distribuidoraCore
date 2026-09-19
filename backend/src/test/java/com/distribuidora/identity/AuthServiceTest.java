package com.distribuidora.identity;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.identity.api.AuthDtos;
import com.distribuidora.identity.application.AuthService;
import com.distribuidora.identity.domain.UserAccount;
import com.distribuidora.identity.infrastructure.UserAccountRepository;
import com.distribuidora.shared.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock UserAccountRepository users;
    @Mock JwtService jwtService;
    @Mock AuditService auditService;

    @Test
    void loginIssuesAccessTokenAndAuditsSuccess() {
        Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        UserAccount user = new UserAccount("admin@distribuidora.local", encoder.encode("Correct123"));
        when(users.findByEmailIgnoreCase("admin@distribuidora.local")).thenReturn(Optional.of(user));
        when(users.findAuthorityCodes(any())).thenReturn(List.of("ADMIN_ALL", "ORDER_CREATE", "STOCK_ADJUST"));
        when(jwtService.issue(user, List.of("ADMIN_ALL", "ORDER_CREATE", "STOCK_ADJUST"))).thenReturn("token");
        when(jwtService.accessTokenSeconds()).thenReturn(900L);

        AuthDtos.LoginResponse response = new AuthService(users, encoder, jwtService, auditService)
            .login(new AuthDtos.LoginRequest("ADMIN@DISTRIBUIDORA.LOCAL", "Correct123"));

        assertThat(response.accessToken()).isEqualTo("token");
        assertThat(response.expiresInSeconds()).isEqualTo(900L);
        verify(auditService).record(any(), any(), any(), any(), any(), any());
        verify(jwtService).issue(user, List.of("ADMIN_ALL", "ORDER_CREATE", "STOCK_ADJUST"));
    }

    @Test
    void invalidPasswordIsRejected() {
        Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        UserAccount user = new UserAccount("admin@distribuidora.local", encoder.encode("Correct123"));
        when(users.findByEmailIgnoreCase("admin@distribuidora.local")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> new AuthService(users, encoder, jwtService, auditService)
            .login(new AuthDtos.LoginRequest("admin@distribuidora.local", "wrong")))
            .isInstanceOf(BadCredentialsException.class);
        verify(auditService).record(any(), any(), any(), any(), any(), any());
    }
}
