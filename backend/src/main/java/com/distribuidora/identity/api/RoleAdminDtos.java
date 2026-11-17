package com.distribuidora.identity.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class RoleAdminDtos {
    private RoleAdminDtos() { }

    public record RoleResponse(UUID id, String code, String description, List<String> permissions,
                               List<String> requiredPermissions, boolean assignedToCurrentUser,
                               boolean currentSessionRevoked, boolean requiresSellerProfile) { }

    public record CreateRoleRequest(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,79}") String code,
                                    @NotBlank @Size(max = 255) String description,
                                    @NotNull Set<@NotBlank String> permissionCodes) { }

    public record PermissionResponse(String code, String description, boolean adminOnly) { }

    public record ReplaceRolePermissionsRequest(@NotNull Set<String> permissionCodes, Set<String> expectedPermissionCodes) {
        public ReplaceRolePermissionsRequest(Set<String> permissionCodes) { this(permissionCodes, null); }
    }
}
