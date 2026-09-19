package com.distribuidora.customer.api;

import com.distribuidora.customer.application.CustomerCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/customers")
public class CustomerCommandController {
    private final CustomerCommandService service;
    public CustomerCommandController(CustomerCommandService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<IdResponse> create(@Valid @RequestBody CustomerPayload body) {
        return ResponseEntity.status(201).body(new IdResponse(service.create(body.input())));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody CustomerPayload body) {
        service.update(id, body.input()); return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<Void> status(@PathVariable UUID id, @RequestBody StatusPayload body) {
        service.setStatus(id, body.status()); return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{customerId}/price-list")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> assignPriceList(
        @PathVariable UUID customerId,
        @RequestBody PriceListPayload body
    ) {
        if (body == null) {
            throw new IllegalArgumentException("priceListId es obligatorio o puede ser null");
        }
        service.assignPriceList(customerId, body.priceListId());
        return ResponseEntity.noContent().build();
    }

    public record CustomerPayload(@NotBlank String businessName, @NotBlank String taxId, UUID sellerId) {
        CustomerCommandService.CustomerInput input() { return new CustomerCommandService.CustomerInput(businessName, taxId, sellerId); }
    }
    public record StatusPayload(String status) { }
    public record PriceListPayload(UUID priceListId) { }
    public record IdResponse(UUID id) { }
}
