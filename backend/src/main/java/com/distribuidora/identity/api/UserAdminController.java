package com.distribuidora.identity.api;

import com.distribuidora.identity.application.UserAdminService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserAdminController {
    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('USER_MANAGE', 'ADMIN_ALL')")
    public ResponseEntity<IdResponse> create(@Valid @RequestBody UserAdminDtos.CreateUserRequest request) {
        return ResponseEntity.status(201).body(new IdResponse(service.create(request)));
    }

    @PostMapping("/invite")
    @PreAuthorize("hasAnyAuthority('USER_MANAGE', 'ADMIN_ALL')")
    public ResponseEntity<UserAdminDtos.InviteUserResponse> invite(@Valid @RequestBody UserAdminDtos.InviteUserRequest request) {
        UserAdminService.InviteUserResult result = service.invite(request);
        return ResponseEntity.status(201).body(new UserAdminDtos.InviteUserResponse(
            result.userId(), result.email(), result.activationToken(), result.expiresAt()));
    }

    @PostMapping("/{id}/revoke-sessions")
    @PreAuthorize("hasAnyAuthority('USER_MANAGE', 'ADMIN_ALL')")
    public ResponseEntity<Void> revokeSessions(@PathVariable UUID id) {
        service.revokeSessions(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/block")
    @PreAuthorize("hasAnyAuthority('USER_MANAGE', 'ADMIN_ALL')")
    public ResponseEntity<Void> block(@PathVariable UUID id) {
        service.blockUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unblock")
    @PreAuthorize("hasAnyAuthority('USER_MANAGE', 'ADMIN_ALL')")
    public ResponseEntity<Void> unblock(@PathVariable UUID id) {
        service.unblockUser(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/role")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> changeRole(@PathVariable UUID id,
                                           @Valid @RequestBody UserAdminDtos.ChangeRoleRequest request) {
        service.changeRole(id, request.role().name());
        return ResponseEntity.noContent().build();
    }

    public record IdResponse(UUID id) { }
}
