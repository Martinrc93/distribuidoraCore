package com.distribuidora.identity.api;

import com.distribuidora.identity.application.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
        return ResponseEntity.ok(authService.login(new AuthDtos.LoginRequest(payload.email(), payload.password())));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthDtos.LoginResponse> refresh(@Valid @RequestBody RefreshPayload payload) {
        return ResponseEntity.ok(authService.refresh(new AuthDtos.RefreshRequest(payload.refreshToken())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutPayload payload) {
        authService.logout(new AuthDtos.LogoutRequest(payload.refreshToken()));
        return ResponseEntity.noContent().build();
    }

    public record LoginPayload(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record RefreshPayload(@NotBlank String refreshToken) {
    }

    public record LogoutPayload(@NotBlank String refreshToken) {
    }
}
