package com.distribuidora.identity;

import com.distribuidora.identity.api.UserAdminController;
import com.distribuidora.identity.api.UserAdminDtos;
import com.distribuidora.identity.application.UserAdminService;
import com.distribuidora.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class UserAdminControllerTest {

    private final UserAdminService service = mock(UserAdminService.class);
    private final UserAdminController controller = new UserAdminController(service);
    private final MockMvc mockMvc = standaloneSetup(controller)
        .setControllerAdvice(new ApiExceptionHandler())
        .build();

    @Test
    void createUserReturns201() throws Exception {
        UUID newId = UUID.randomUUID();
        when(service.create(any())).thenReturn(newId);

        mockMvc.perform(post("/api/users")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                        "email": "newuser@distribuidora.local",
                        "temporaryPassword": "TemporaryPassword123",
                        "role": "ADMIN"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(newId.toString()));

        verify(service).create(any());
    }

    @Test
    void inviteUserReturns201WithActivationToken() throws Exception {
        UUID newId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(1800);
        when(service.invite(any())).thenReturn(
            new UserAdminDtos.InviteUserResponse(newId, "invited@distribuidora.local", "token-xyz", expiresAt)
        );

        mockMvc.perform(post("/api/users/invite")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                        "email": "invited@distribuidora.local",
                        "role": "SELLER",
                        "displayName": "Vendedor Dos"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.userId").value(newId.toString()))
            .andExpect(jsonPath("$.email").value("invited@distribuidora.local"))
            .andExpect(jsonPath("$.activationToken").value("token-xyz"));

        verify(service).invite(any());
    }

    @Test
    void inviteUserWithInvalidDataReturns400() throws Exception {
        mockMvc.perform(post("/api/users/invite")
                .contentType(APPLICATION_JSON)
                .content("""
                    {
                        "email": "not-an-email",
                        "role": null
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void revokeSessionsReturns204() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post("/api/users/{id}/revoke-sessions", userId))
            .andExpect(status().isNoContent());

        verify(service).revokeSessions(userId);
    }

    @Test
    void blockUserReturns204() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post("/api/users/{id}/block", userId))
            .andExpect(status().isNoContent());

        verify(service).blockUser(userId);
    }

    @Test
    void unblockUserReturns204() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(post("/api/users/{id}/unblock", userId))
            .andExpect(status().isNoContent());

        verify(service).unblockUser(userId);
    }
}
