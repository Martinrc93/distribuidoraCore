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
    @PreAuthorize("hasAnyAuthority('CUSTOMER_CREATE', 'ADMIN_ALL')")
    public ResponseEntity<IdResponse> create(@Valid @RequestBody CustomerPayload body) {
        return ResponseEntity.status(201).body(new IdResponse(service.create(body.input())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_EDIT', 'ADMIN_ALL')")
    public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody CustomerPayload body) {
        service.update(id, body.input()); return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_STATUS_CHANGE', 'ADMIN_ALL')")
    public ResponseEntity<Void> status(@PathVariable UUID id, @RequestBody StatusPayload body) {
        service.setStatus(id, body.status()); return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{customerId}/price-list")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_PRICE_LIST_CHANGE', 'ADMIN_ALL')")
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

    @PatchMapping("/{customerId}/kind")
    @PreAuthorize("hasAuthority('ADMIN_ALL') and hasAuthority('RESELLER_MANAGE')")
    public ResponseEntity<Void> changeKind(@PathVariable UUID customerId, @RequestBody KindPayload body) {
        if (body == null) throw new IllegalArgumentException("kind es obligatorio");
        service.changeKind(customerId, body.kind());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{customerId}/reseller")
    @PreAuthorize("hasAuthority('ADMIN_ALL') and hasAuthority('RESELLER_MANAGE')")
    public ResellerResponse assignReseller(@PathVariable UUID customerId, @RequestBody ResellerPayload body) {
        if (body == null) throw new IllegalArgumentException("resellerId es obligatorio o puede ser null");
        var result = service.assignReseller(customerId, body.resellerId());
        return new ResellerResponse(result.customerId(), result.resellerId(), result.pendingOrdersKept());
    }

    public record KindPayload(String kind) { }
    public record ResellerPayload(UUID resellerId) { }
    public record ResellerResponse(UUID customerId, UUID resellerId, long pendingOrdersKept) { }

    public record CustomerPayload(@NotBlank String businessName, String cuitId, String email, String phone, String address, String zone, UUID sellerId, UUID priceListId) {
        CustomerCommandService.CustomerInput input() { return new CustomerCommandService.CustomerInput(businessName, cuitId, email, phone, address, zone, sellerId, priceListId); }
    }
    public record StatusPayload(String status) { }
    public record PriceListPayload(UUID priceListId) { }
    public record IdResponse(UUID id) { }
}
