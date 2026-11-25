package com.distribuidora.customer.api;

import com.distribuidora.customer.application.CustomerCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/customers")
public class CustomerCommandController {
    private final CustomerCommandService service;
    public CustomerCommandController(CustomerCommandService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('CUSTOMER_CREATE', 'ADMIN_ALL')")
    public ResponseEntity<IdResponse> create(@Valid @RequestBody CustomerPayload body) {
        return ResponseEntity.status(201).body(new IdResponse(service.create(body.input(), body.kind())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_EDIT', 'ADMIN_ALL')")
    public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody CustomerPayload body) {
        service.update(id, body.input()); return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/contact")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_EDIT', 'ADMIN_ALL')")
    public ResponseEntity<Void> contact(@PathVariable UUID id, @Valid @RequestBody ContactPayload body) {
        service.updateContact(id, body.email(), body.phone());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_STATUS_CHANGE', 'ADMIN_ALL')")
    public ResponseEntity<Void> status(@PathVariable UUID id, @Valid @RequestBody StatusPayload body) {
        service.setStatus(id, body.status()); return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{customerId}/price-list")
    @PreAuthorize("hasAnyAuthority('CUSTOMER_PRICE_LIST_CHANGE', 'ADMIN_ALL')")
    public ResponseEntity<Void> assignPriceList(
        @PathVariable UUID customerId,
        @Valid @RequestBody PriceListPayload body
    ) {
        if (body == null) {
            throw new IllegalArgumentException("priceListId es obligatorio o puede ser null");
        }
        service.assignPriceList(customerId, body.priceListId());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{customerId}/kind")
    @PreAuthorize("hasAuthority('ADMIN_ALL') and hasAuthority('RESELLER_MANAGE')")
    public ResponseEntity<Void> changeKind(@PathVariable UUID customerId, @Valid @RequestBody KindPayload body) {
        if (body == null) throw new IllegalArgumentException("kind es obligatorio");
        service.changeKind(customerId, body.kind());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{customerId}/reseller")
    @PreAuthorize("hasAuthority('ADMIN_ALL') and hasAuthority('RESELLER_MANAGE')")
    public ResellerResponse assignReseller(@PathVariable UUID customerId, @Valid @RequestBody ResellerPayload body) {
        if (body == null) throw new IllegalArgumentException("resellerId es obligatorio o puede ser null");
        var result = service.assignReseller(customerId, body.resellerId());
        return new ResellerResponse(result.customerId(), result.resellerId(), result.pendingOrdersKept());
    }

    public record KindPayload(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "REGULAR|RESELLER") String kind) { }
    public record ResellerPayload(UUID resellerId) { }
    public record ResellerResponse(UUID customerId, UUID resellerId, long pendingOrdersKept) { }

    public record CustomerPayload(
        @NotBlank @jakarta.validation.constraints.Size(max = 200) String businessName,
        @jakarta.validation.constraints.Size(max = 40) String cuitId,
        @jakarta.validation.constraints.Email @jakarta.validation.constraints.Size(max = 254) String email,
        @jakarta.validation.constraints.Size(max = 40) String phone,
        @jakarta.validation.constraints.Size(max = 300) String address,
        @jakarta.validation.constraints.Size(max = 120) String zone,
        UUID sellerId,
        UUID priceListId,
        @jakarta.validation.constraints.Pattern(regexp = "REGULAR|RESELLER") String kind) {
        CustomerCommandService.CustomerInput input() { return new CustomerCommandService.CustomerInput(businessName, cuitId, email, phone, address, zone, sellerId, priceListId); }
    }
    public record StatusPayload(@NotBlank @jakarta.validation.constraints.Pattern(regexp = "ACTIVE|INACTIVE") String status) { }
    public record ContactPayload(@Email @Size(max = 254) String email,
                                 @Pattern(regexp = "\\+[1-9][0-9]{7,14}") String phone) { }
    public record PriceListPayload(UUID priceListId) { }
    public record IdResponse(UUID id) { }
}
