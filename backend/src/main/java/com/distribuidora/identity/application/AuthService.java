package com.distribuidora.identity.application;

import com.distribuidora.identity.api.AuthDtos;
import com.distribuidora.audit.application.AuditService;
import com.distribuidora.identity.domain.RefreshToken;
import com.distribuidora.identity.domain.UserAccount;
import com.distribuidora.identity.domain.UserStatus;
import com.distribuidora.identity.infrastructure.RefreshTokenRepository;
import com.distribuidora.identity.infrastructure.UserAccountRepository;
import com.distribuidora.shared.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private final UserAccountRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final long refreshTokenDays;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
        UserAccountRepository users,
        RefreshTokenRepository refreshTokens,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        AuditService auditService,
        @Value("${app.security.refresh-token-days:7}") long refreshTokenDays
    ) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.refreshTokenDays = refreshTokenDays;
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

        String rawRefreshToken = generateRawToken();
        String tokenHash = hashToken(rawRefreshToken);
        Instant expiresAt = Instant.now().plus(Duration.ofDays(refreshTokenDays));

        RefreshToken refreshToken = new RefreshToken(user.getId(), tokenHash, expiresAt);
        refreshTokens.save(refreshToken);

        auditService.record(user.getId(), "LOGIN", "USER", userId, "SUCCESS", Map.of());
        return new AuthDtos.LoginResponse(
            jwtService.issue(user, users.findAuthorityCodes(user.getId())),
            "Bearer",
            jwtService.accessTokenSeconds(),
            rawRefreshToken
        );
    }

    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public AuthDtos.LoginResponse refresh(AuthDtos.RefreshRequest request) {
        if (request.refreshToken() == null || request.refreshToken().isBlank()) {
            throw new InvalidRefreshTokenException("Token de refresco requerido");
        }

        String tokenHash = hashToken(request.refreshToken().trim());
        RefreshToken oldToken = refreshTokens.findByTokenHash(tokenHash).orElse(null);

        if (oldToken == null) {
            auditService.record(null, "TOKEN_REFRESH", "USER", tokenHash, "FAILURE", Map.of("reason", "token_not_found"));
            throw new InvalidRefreshTokenException("Token de refresco inválido");
        }

        Instant now = Instant.now();
        UUID userId = oldToken.getUserId();

        if (oldToken.isRevoked()) {
            refreshTokens.revokeAllByUserId(userId, now);
            auditService.record(userId, "TOKEN_REUSE_DETECTED", "USER", userId.toString(), "FAILURE",
                Map.of("reason", "token_already_revoked", "token_hash", tokenHash));
            throw new InvalidRefreshTokenException("Token de refresco revocado. Posible reuso detectado.");
        }

        if (oldToken.isExpired(now)) {
            oldToken.revoke(now);
            auditService.record(userId, "TOKEN_REFRESH", "USER", userId.toString(), "FAILURE",
                Map.of("reason", "token_expired"));
            throw new InvalidRefreshTokenException("Token de refresco expirado");
        }

        UserAccount user = users.findById(userId).orElse(null);
        if (user == null || user.getStatus() != UserStatus.ACTIVE) {
            oldToken.revoke(now);
            auditService.record(userId, "TOKEN_REFRESH", "USER", userId.toString(), "FAILURE",
                Map.of("reason", "user_inactive_or_missing"));
            throw new InvalidRefreshTokenException("Usuario inactivo o no encontrado");
        }

        // Rotate token
        String newRawToken = generateRawToken();
        String newTokenHash = hashToken(newRawToken);
        Instant expiresAt = now.plus(Duration.ofDays(refreshTokenDays));

        RefreshToken newToken = new RefreshToken(userId, newTokenHash, expiresAt);
        refreshTokens.save(newToken);

        oldToken.replaceWith(newToken.getId(), now);
        refreshTokens.save(oldToken);

        String newAccessToken = jwtService.issue(user, users.findAuthorityCodes(userId));
        auditService.record(userId, "TOKEN_REFRESH", "USER", userId.toString(), "SUCCESS", Map.of());

        return new AuthDtos.LoginResponse(newAccessToken, "Bearer", jwtService.accessTokenSeconds(), newRawToken);
    }

    @Transactional
    public void logout(AuthDtos.LogoutRequest request) {
        if (request.refreshToken() == null || request.refreshToken().isBlank()) {
            return;
        }

        String tokenHash = hashToken(request.refreshToken().trim());
        refreshTokens.findByTokenHash(tokenHash).ifPresent(token -> {
            if (!token.isRevoked()) {
                token.revoke(Instant.now());
                refreshTokens.save(token);
                auditService.record(token.getUserId(), "LOGOUT", "USER", token.getUserId().toString(), "SUCCESS", Map.of());
            }
        });
    }

    public static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private String generateRawToken() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    private BadCredentialsException badCredentials() {
        return new BadCredentialsException("Credenciales inválidas");
    }
}
