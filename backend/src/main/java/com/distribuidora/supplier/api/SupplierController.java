package com.distribuidora.supplier.api;

import com.distribuidora.shared.web.PageResponse;
import com.distribuidora.supplier.application.SupplierService;
import com.distribuidora.supplier.application.SupplierService.Supplier;
import com.distribuidora.supplier.application.SupplierService.SupplierInput;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/suppliers")
@PreAuthorize("hasAuthority('ADMIN_ALL')")
public class SupplierController {
    private final SupplierService service;

    public SupplierController(SupplierService service) { this.service = service; }

    @GetMapping
    public PageResponse<Supplier> list(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(defaultValue = "") String search) {
        return service.list(page, size, search);
    }

    @PostMapping
    public ResponseEntity<IdResponse> create(@Valid @RequestBody SupplierInput input) {
        return ResponseEntity.status(201).body(new IdResponse(service.create(input)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody SupplierInput input) {
        service.update(id, input);
        return ResponseEntity.noContent().build();
    }

    public record IdResponse(UUID id) { }
}
