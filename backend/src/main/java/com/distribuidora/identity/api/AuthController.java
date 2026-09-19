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

    public record LoginPayload(@Email @NotBlank String email, @NotBlank String password) {
    }
}
