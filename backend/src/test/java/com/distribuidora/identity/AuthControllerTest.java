package com.distribuidora.identity;

import com.distribuidora.identity.api.AuthController;
import com.distribuidora.identity.api.AuthDtos;
import com.distribuidora.identity.api.AuthRateLimiter;
import com.distribuidora.identity.application.AuthService;
import com.distribuidora.identity.application.InvalidRefreshTokenException;
import com.distribuidora.identity.api.IdentityExceptionHandler;
import com.distribuidora.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AuthControllerTest {

    private final AuthService authService = mock(AuthService.class);
    private final AuthController controller = new AuthController(authService);
    private final MockMvc mockMvc = standaloneSetup(controller)
        .setControllerAdvice(new IdentityExceptionHandler(), new ApiExceptionHandler())
        .build();

    @Test
    void loginReturnsAccessTokenAndRefreshToken() throws Exception {
        when(authService.login(any())).thenReturn(
            new AuthService.LoginResult("access-123", "Bearer", 900L, "refresh-456")
        );

        mockMvc.perform(post("/api/auth/login")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                        "email": "admin@distribuidora.local",
                        "password": "Password123"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("access-123"))
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresInSeconds").value(900))
            .andExpect(jsonPath("$.refreshToken").value("refresh-456"));

        verify(authService).login(any());
    }

    @Test
    void refreshReturnsNewTokens() throws Exception {
        when(authService.refresh(any())).thenReturn(
            new AuthService.LoginResult("new-access-789", "Bearer", 900L, "new-refresh-999")
        );

        mockMvc.perform(post("/api/auth/refresh")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                        "refreshToken": "old-refresh-123"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("new-access-789"))
            .andExpect(jsonPath("$.refreshToken").value("new-refresh-999"));

        verify(authService).refresh(any());
    }

    @Test
    void refreshWithInvalidTokenReturns401() throws Exception {
        when(authService.refresh(any())).thenThrow(
            new InvalidRefreshTokenException("Token de refresco revocado. Posible reuso detectado.")
        );

        mockMvc.perform(post("/api/auth/refresh")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                        "refreshToken": "compromised-token"
                    }
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
            .andExpect(jsonPath("$.detail").value("Token de refresco revocado. Posible reuso detectado."));
    }

    @Test
    void refreshWithBlankTokenReturns400() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                        "refreshToken": ""
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void logoutReturns204() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                        "refreshToken": "token-to-revoke"
                    }
                    """))
            .andExpect(status().isNoContent());

        verify(authService).logout(any());
    }

    @Test
    void activateReturns204() throws Exception {
        mockMvc.perform(post("/api/auth/activate")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                        "activationToken": "valid-token-123",
                        "password": "NewStrongPassword123"
                    }
                    """))
            .andExpect(status().isNoContent());

        verify(authService).activate(any());
    }

    @Test
    void activateWithInvalidTokenReturns400() throws Exception {
        org.mockito.Mockito.doThrow(new com.distribuidora.identity.application.InvalidActivationTokenException("Token de activación inválido"))
            .when(authService).activate(any());

        mockMvc.perform(post("/api/auth/activate")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                        "activationToken": "bad-token",
                        "password": "NewStrongPassword123"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_ACTIVATION_TOKEN"));
    }

    @Test
    void loginRejectsOverlongEmailAndPasswordWith400() throws Exception {
        String longEmail = "a".repeat(310) + "@example.com";
        mockMvc.perform(post("/api/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"" + longEmail + "\",\"password\":\"Password123\"}"))
            .andExpect(status().isBadRequest());

        String longPassword = "p".repeat(201);
        mockMvc.perform(post("/api/auth/login")
                .contentType(APPLICATION_JSON)
                .content("{\"email\":\"user@example.com\",\"password\":\"" + longPassword + "\"}"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(authService);
    }

    @Test
    void rateLimiterReturns429WithRetryAfterWhenExceeded() throws Exception {
        AuthService limitedAuth = mock(AuthService.class);
        when(limitedAuth.login(any())).thenReturn(
            new AuthService.LoginResult("access-123", "Bearer", 900L, "refresh-456")
        );
        AuthRateLimiter limiter = new AuthRateLimiter(
            true, 60, 2, 10, 10, 10, Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC));
        MockMvc limitedMvc = standaloneSetup(new AuthController(limitedAuth, limiter))
            .setControllerAdvice(new IdentityExceptionHandler(), new ApiExceptionHandler())
            .build();

        String body = "{\"email\":\"user@example.com\",\"password\":\"Password123\"}";
        limitedMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        limitedMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        limitedMvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content(body))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "60"))
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }
}
