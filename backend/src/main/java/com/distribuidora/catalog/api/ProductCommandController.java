package com.distribuidora.catalog.api;

import com.distribuidora.catalog.application.ProductCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
public class ProductCommandController {
    private final ProductCommandService service;
    public ProductCommandController(ProductCommandService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<IdResponse> create(@Valid @RequestBody ProductPayload body) {
        return ResponseEntity.status(201).body(new IdResponse(service.create(body.input())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody ProductPayload body) {
        service.update(id, body.input()); return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> status(@PathVariable UUID id, @RequestBody StatusPayload body) {
        service.setStatus(id, body.status()); return ResponseEntity.noContent().build();
    }

    public record ProductPayload(@NotBlank String sku, @NotBlank String name, @NotBlank String category,
                                 @NotBlank String presentation, @NotNull @DecimalMin("0") BigDecimal cost,
                                 @NotNull @DecimalMin("0") BigDecimal price) {
        ProductCommandService.ProductInput input() { return new ProductCommandService.ProductInput(sku, name, category, presentation, cost, price); }
    }
    public record StatusPayload(String status) { }
    public record IdResponse(UUID id) { }
}
