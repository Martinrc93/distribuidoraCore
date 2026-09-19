package com.distribuidora.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class UserAdminDtos {
    private UserAdminDtos() { }

    public record CreateUserRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 200) String temporaryPassword,
        @NotNull Role role,
        @Size(max = 160) String displayName
    ) { }

    public enum Role { ADMIN, SELLER }
}
