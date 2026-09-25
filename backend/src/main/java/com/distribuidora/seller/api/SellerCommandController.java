package com.distribuidora.seller.api;

import com.distribuidora.seller.application.SellerCommandService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/sellers")
public class SellerCommandController {
    private final SellerCommandService service;

    public SellerCommandController(SellerCommandService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<SellerDtos.IdResponse> create(@Valid @RequestBody SellerDtos.CreateSellerRequest request) {
        UUID id = service.create(request);
        return ResponseEntity.status(201).body(new SellerDtos.IdResponse(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody SellerDtos.UpdateSellerRequest request) {
        service.update(id, request);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> status(@PathVariable UUID id, @Valid @RequestBody SellerDtos.SellerStatusRequest request) {
        service.setStatus(id, request.status());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> activate(@PathVariable UUID id) {
        service.activate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reassign-customers")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<SellerDtos.ReassignCustomersResponse> reassignCustomers(
            @Valid @RequestBody SellerDtos.ReassignCustomersRequest request) {
        SellerCommandService.ReassignCustomersResult result = service.reassignCustomers(request);
        return ResponseEntity.ok(new SellerDtos.ReassignCustomersResponse(result.sourceSellerId(),
            result.targetSellerId(), result.reassignedCustomersCount(), result.reassignedOrdersCount()));
    }

    @PostMapping("/reassign-orders")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<SellerDtos.ReassignOrdersResponse> reassignOrders(
            @Valid @RequestBody SellerDtos.ReassignOrdersRequest request) {
        SellerCommandService.ReassignOrdersResult result = service.reassignOrders(request);
        return ResponseEntity.ok(new SellerDtos.ReassignOrdersResponse(result.targetSellerId(),
            result.reassignedOrdersCount()));
    }
}
