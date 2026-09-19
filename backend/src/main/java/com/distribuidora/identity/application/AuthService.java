package com.distribuidora.identity.application;

import com.distribuidora.identity.api.AuthDtos;
import com.distribuidora.audit.application.AuditService;
import com.distribuidora.identity.domain.UserAccount;
import com.distribuidora.identity.domain.UserStatus;
import com.distribuidora.identity.infrastructure.UserAccountRepository;
import com.distribuidora.shared.security.JwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;

@Service
public class AuthService {
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;

    public AuthService(UserAccountRepository users, PasswordEncoder passwordEncoder, JwtService jwtService, AuditService auditService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    @Transactional(noRollbackFor = BadCredentialsException.class)
    public AuthDtos.LoginResponse login(AuthDtos.LoginRequest request) {
        String email = request.email() == null ? "" : request.email().trim().toLowerCase(Locale.ROOT);
        UserAccount user = users.findByEmailIgnoreCase(email).orElse(null);
        if (user == null) {
            auditService.record(null, "LOGIN", "USER", email, "FAILURE", Map.of("reason", "invalid_credentials"));
            throw badCredentials();
        }

        if (user.getStatus() != UserStatus.ACTIVE || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.registerFailedLogin();
            String userId = user.getId() == null ? null : user.getId().toString();
            auditService.record(user.getId(), "LOGIN", "USER", userId, "FAILURE", Map.of("reason", "invalid_credentials"));
            throw badCredentials();
        }

        user.resetFailedLogins();
        String userId = user.getId() == null ? null : user.getId().toString();
        auditService.record(user.getId(), "LOGIN", "USER", userId, "SUCCESS", Map.of());
        return new AuthDtos.LoginResponse(jwtService.issue(user, users.findAuthorityCodes(user.getId())),
            "Bearer", jwtService.accessTokenSeconds());
    }

    private BadCredentialsException badCredentials() {
        return new BadCredentialsException("Credenciales inválidas");
    }
}
