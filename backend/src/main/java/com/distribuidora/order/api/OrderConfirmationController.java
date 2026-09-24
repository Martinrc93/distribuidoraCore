package com.distribuidora.order.api;

import com.distribuidora.order.application.OrderConfirmationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
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
    @PreAuthorize("hasAnyAuthority('ORDER_CREATE', 'ADMIN_ALL')")
    public ResponseEntity<OrderConfirmationDtos.ConfirmationResponse> confirm(
        @Valid @RequestBody OrderConfirmationDtos.ConfirmationRequest request) {
        return ResponseEntity.status(201).body(toResponse(service.confirm(request)));
    }

    @PutMapping("/{orderId}")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<OrderEditDtos.EditResponse> editConfirmed(
        @PathVariable java.util.UUID orderId, @Valid @RequestBody OrderEditDtos.EditRequest request) {
        OrderConfirmationService.EditResult result = service.editConfirmed(orderId, request);
        return ResponseEntity.ok(new OrderEditDtos.EditResponse(result.orderId(), result.saleId(),
            result.total(), result.paid(), result.balance()));
    }

    private static OrderConfirmationDtos.ConfirmationResponse toResponse(OrderConfirmationService.ConfirmationResult result) {
        OrderConfirmationDtos.CreditLimitWarning warning = result.creditLimitWarning() == null ? null
            : new OrderConfirmationDtos.CreditLimitWarning(result.creditLimitWarning().creditLimit(),
                result.creditLimitWarning().projectedBalance(), result.creditLimitWarning().exceededBy());
        return new OrderConfirmationDtos.ConfirmationResponse(result.orderId(), result.saleId(), result.orderNumber(),
            result.saleNumber(), result.total(), result.paid(), result.balance(), warning);
    }
}
