package com.distribuidora.order.api;

import com.distribuidora.order.application.OrderFulfillmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderFulfillmentController {
    private final OrderFulfillmentService service;

    public OrderFulfillmentController(OrderFulfillmentService service) { this.service = service; }

    @PostMapping("/{orderId}/prepare")
    @PreAuthorize("hasAnyAuthority('ORDER_PREPARE', 'ADMIN_ALL')")
    public ResponseEntity<Void> prepare(@PathVariable UUID orderId) {
        service.prepare(orderId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{orderId}/review")
    @PreAuthorize("hasAuthority('ADMIN_ALL') and hasAuthority('ORDER_REVIEW')")
    public ResponseEntity<Void> review(@PathVariable UUID orderId) {
        service.review(orderId);
        return ResponseEntity.noContent().build();
    }
}
