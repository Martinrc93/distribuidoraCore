package com.distribuidora.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import com.distribuidora.identity.application.UserAdminService;

import java.time.Instant;
import java.util.UUID;

public final class UserAdminDtos {
    private UserAdminDtos() { }

    public record CreateUserRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 200) String temporaryPassword,
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,79}") String role,
        @Size(max = 160) String displayName
    ) implements UserAdminService.CreateUserCommand {
        @Override public String roleCode() { return role; }
    }

    public record InviteUserRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,79}") String role,
        @Size(max = 160) String displayName
    ) implements UserAdminService.InviteUserCommand {
        @Override public String roleCode() { return role; }
    }

    public record InviteUserResponse(
        UUID userId,
        String email,
        String deliveryStatus,
        Instant expiresAt
    ) { }

    public record ChangeRoleRequest(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,79}") String role) { }
}
