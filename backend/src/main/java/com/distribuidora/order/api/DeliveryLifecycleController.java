package com.distribuidora.order.api;

import com.distribuidora.order.application.DeliveryLifecycleService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class DeliveryLifecycleController {
    private final DeliveryLifecycleService service;

    public DeliveryLifecycleController(DeliveryLifecycleService service) {
        this.service = service;
    }

    @PostMapping("/{id}/delivery-attempts")
    @PreAuthorize("hasAnyAuthority('SALE_DELIVER', 'ADMIN_ALL')")
    public ResponseEntity<Void> recordAttempt(@PathVariable UUID id,
                                              @Valid @RequestBody DeliveryLifecycleDtos.DeliveryAttemptRequest request) {
        service.recordAttempt(id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        service.cancel(id);
        return ResponseEntity.noContent().build();
    }
}
