package com.distribuidora.identity.api;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record LoginRequest(String email, String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds) {
    }
}
