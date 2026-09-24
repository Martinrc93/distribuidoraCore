package com.distribuidora.catalog.api;

import com.distribuidora.catalog.application.BrandService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/brands")
public class BrandController {
    private final BrandService service;

    public BrandController(BrandService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<CatalogAdminDtos.BrandResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(service.list(search, status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CatalogAdminDtos.BrandResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<CatalogAdminDtos.IdResponse> create(@Valid @RequestBody CatalogAdminDtos.CreateBrandRequest request) {
        UUID id = service.create(request);
        return ResponseEntity.status(201).body(new CatalogAdminDtos.IdResponse(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody CatalogAdminDtos.UpdateBrandRequest request) {
        service.update(id, request);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> status(@PathVariable UUID id, @Valid @RequestBody CatalogAdminDtos.StatusRequest request) {
        service.setStatus(id, request.status());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
