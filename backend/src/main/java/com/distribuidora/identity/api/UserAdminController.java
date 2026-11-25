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
@PreAuthorize("hasAuthority('ADMIN_ALL') and hasAuthority('USER_MANAGE')")
public class UserAdminController {
    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<IdResponse> create(@Valid @RequestBody UserAdminDtos.CreateUserRequest request) {
        return ResponseEntity.status(201).body(new IdResponse(service.create(request)));
    }

    @PostMapping("/invite")
    public ResponseEntity<UserAdminDtos.InviteUserResponse> invite(@Valid @RequestBody UserAdminDtos.InviteUserRequest request) {
        UserAdminService.InviteUserResult result = service.invite(request);
        return ResponseEntity.status(201).body(new UserAdminDtos.InviteUserResponse(
            result.userId(), result.email(), result.deliveryStatus(), result.expiresAt()));
    }

    @PostMapping("/{id}/revoke-sessions")
    public ResponseEntity<Void> revokeSessions(@PathVariable UUID id) {
        service.revokeSessions(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/block")
    public ResponseEntity<Void> block(@PathVariable UUID id) {
        service.blockUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unblock")
    public ResponseEntity<Void> unblock(@PathVariable UUID id) {
        service.unblockUser(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/role")
    public ResponseEntity<Void> changeRole(@PathVariable UUID id,
                                           @Valid @RequestBody UserAdminDtos.ChangeRoleRequest request) {
        service.changeRole(id, request.role());
        return ResponseEntity.noContent().build();
    }

    public record IdResponse(UUID id) { }
}
