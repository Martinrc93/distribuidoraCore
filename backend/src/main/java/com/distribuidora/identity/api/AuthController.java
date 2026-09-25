package com.distribuidora.identity.api;

import com.distribuidora.identity.application.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.LoginResponse> login(@Valid @RequestBody LoginPayload payload) {
        AuthService.LoginResult result = authService.login(new AuthDtos.LoginRequest(payload.email(), payload.password()));
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthDtos.LoginResponse> refresh(@Valid @RequestBody RefreshPayload payload) {
        AuthService.LoginResult result = authService.refresh(new AuthDtos.RefreshRequest(payload.refreshToken()));
        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutPayload payload) {
        authService.logout(new AuthDtos.LogoutRequest(payload.refreshToken()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/activate")
    public ResponseEntity<Void> activate(@Valid @RequestBody ActivatePayload payload) {
        authService.activate(new AuthDtos.ActivateUserRequest(payload.activationToken(), payload.password()));
        return ResponseEntity.noContent().build();
    }

    private static AuthDtos.LoginResponse toResponse(AuthService.LoginResult result) {
        return new AuthDtos.LoginResponse(result.accessToken(), result.tokenType(), result.expiresInSeconds(),
            result.refreshToken());
    }

    public record LoginPayload(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record RefreshPayload(@NotBlank String refreshToken) {
    }

    public record LogoutPayload(@NotBlank String refreshToken) {
    }

    public record ActivatePayload(@NotBlank String activationToken, @NotBlank @Size(min = 8, max = 200) String password) {
    }
}
