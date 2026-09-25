package com.distribuidora.identity.api;

import com.distribuidora.identity.application.AuthService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record LoginRequest(String email, String password) implements AuthService.LoginCommand {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds, String refreshToken) {
        public LoginResponse(String accessToken, String tokenType, long expiresInSeconds) {
            this(accessToken, tokenType, expiresInSeconds, null);
        }
    }

    public record RefreshRequest(@NotBlank String refreshToken) implements AuthService.RefreshCommand {
    }

    public record LogoutRequest(@NotBlank String refreshToken) implements AuthService.LogoutCommand {
    }

    public record ActivateUserRequest(
        @NotBlank String activationToken,
        @NotBlank @Size(min = 8, max = 200) String password
    ) implements AuthService.ActivateUserCommand {
    }
}
