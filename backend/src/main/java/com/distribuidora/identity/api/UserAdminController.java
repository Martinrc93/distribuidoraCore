package com.distribuidora.identity.api;

import com.distribuidora.identity.application.UserAdminService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

    public record IdResponse(UUID id) { }
}
