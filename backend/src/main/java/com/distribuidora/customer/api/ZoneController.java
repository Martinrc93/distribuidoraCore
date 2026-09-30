package com.distribuidora.customer.api;

import com.distribuidora.customer.application.ZoneService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/zones")
public class ZoneController {
    private final ZoneService service;

    public ZoneController(ZoneService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public List<ZoneService.Zone> list() { return service.list(); }

    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<IdResponse> create(@Valid @RequestBody ZoneRequest request) {
        return ResponseEntity.status(201).body(new IdResponse(service.create(request.name())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> rename(@PathVariable UUID id, @Valid @RequestBody ZoneRequest request) {
        service.rename(id, request.name());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record ZoneRequest(@NotBlank @Size(max = 120) String name) { }
    public record IdResponse(UUID id) { }
}
