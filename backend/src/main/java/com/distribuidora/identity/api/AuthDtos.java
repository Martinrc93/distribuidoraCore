package com.distribuidora.identity.api;

import jakarta.validation.constraints.NotBlank;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record LoginRequest(String email, String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds, String refreshToken) {
        public LoginResponse(String accessToken, String tokenType, long expiresInSeconds) {
            this(accessToken, tokenType, expiresInSeconds, null);
        }
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record LogoutRequest(@NotBlank String refreshToken) {
    }
}
