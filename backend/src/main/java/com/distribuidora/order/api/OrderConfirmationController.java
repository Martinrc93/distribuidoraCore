package com.distribuidora.order.api;

import com.distribuidora.order.application.OrderConfirmationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderConfirmationController {
    private final OrderConfirmationService service;

    public OrderConfirmationController(OrderConfirmationService service) {
        this.service = service;
    }

    @PostMapping("/confirm")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public ResponseEntity<OrderConfirmationDtos.ConfirmationResponse> confirm(
        @Valid @RequestBody OrderConfirmationDtos.ConfirmationRequest request) {
        return ResponseEntity.status(201).body(service.confirm(request));
    }
}
