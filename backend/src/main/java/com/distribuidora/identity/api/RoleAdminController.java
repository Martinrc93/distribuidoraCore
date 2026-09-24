package com.distribuidora.identity.api;

import com.distribuidora.identity.application.RoleAdminService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class RoleAdminController {
    private final RoleAdminService service;

    public RoleAdminController(RoleAdminService service) {
        this.service = service;
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public List<RoleAdminDtos.RoleResponse> listRoles() {
        return service.listRoles().stream().map(RoleAdminController::toRoleResponse).toList();
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public List<RoleAdminDtos.PermissionResponse> listPermissions() {
        return service.listPermissions().stream()
            .map(permission -> new RoleAdminDtos.PermissionResponse(permission.code(), permission.description()))
            .toList();
    }

    @PutMapping("/roles/{roleCode}/permissions")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<RoleAdminDtos.RoleResponse> replaceRolePermissions(
        @PathVariable String roleCode,
        @Valid @RequestBody RoleAdminDtos.ReplaceRolePermissionsRequest request
    ) {
        return ResponseEntity.ok(toRoleResponse(service.replacePermissions(roleCode, request.permissionCodes())));
    }

    private static RoleAdminDtos.RoleResponse toRoleResponse(RoleAdminService.RoleView role) {
        return new RoleAdminDtos.RoleResponse(role.id(), role.code(), role.description(), role.permissions());
    }
}
