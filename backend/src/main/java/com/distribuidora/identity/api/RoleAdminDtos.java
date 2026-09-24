package com.distribuidora.identity.api;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class RoleAdminDtos {
    private RoleAdminDtos() { }

    public record RoleResponse(UUID id, String code, String description, List<String> permissions) { }

    public record PermissionResponse(String code, String description) { }

    public record ReplaceRolePermissionsRequest(@NotNull Set<String> permissionCodes) { }
}
