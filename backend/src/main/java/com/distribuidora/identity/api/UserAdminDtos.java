package com.distribuidora.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.distribuidora.identity.application.UserAdminService;

import java.time.Instant;
import java.util.UUID;

public final class UserAdminDtos {
    private UserAdminDtos() { }

    public record CreateUserRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 200) String temporaryPassword,
        @NotNull Role role,
        @Size(max = 160) String displayName
    ) implements UserAdminService.CreateUserCommand {
        @Override public String roleCode() { return role == null ? null : role.name(); }
    }

    public record InviteUserRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotNull Role role,
        @Size(max = 160) String displayName
    ) implements UserAdminService.InviteUserCommand {
        @Override public String roleCode() { return role == null ? null : role.name(); }
    }

    public record InviteUserResponse(
        UUID userId,
        String email,
        String activationToken,
        Instant expiresAt
    ) { }

    public record ChangeRoleRequest(@NotNull Role role) { }

    public enum Role { ADMIN, SELLER }
}
