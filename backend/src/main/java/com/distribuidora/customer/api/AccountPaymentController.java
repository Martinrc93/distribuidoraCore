package com.distribuidora.customer.api;

import com.distribuidora.customer.application.AccountPaymentService;
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
@RequestMapping("/api/customers/{customerId}/account-payments")
public class AccountPaymentController {
    private final AccountPaymentService service;

    public AccountPaymentController(AccountPaymentService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAnyAuthority('SALE_PAYMENT', 'ADMIN_ALL')")
    public ResponseEntity<AccountPaymentDtos.PaymentResponse> apply(
        @PathVariable UUID customerId, @Valid @RequestBody AccountPaymentDtos.PaymentRequest request) {
        return response(service.apply(customerId, request));
    }

    @PostMapping("/settlement")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<AccountPaymentDtos.PaymentResponse> settle(
        @PathVariable UUID customerId, @Valid @RequestBody AccountPaymentDtos.SettlementRequest request) {
        return response(service.settle(customerId, request.payments()));
    }

    private ResponseEntity<AccountPaymentDtos.PaymentResponse> response(AccountPaymentService.PaymentResult result) {
        var allocations = result.allocations().stream()
            .map(item -> new AccountPaymentDtos.Allocation(item.saleId(), item.saleNumber(), item.paymentId(), item.amount()))
            .toList();
        return ResponseEntity.status(201).body(new AccountPaymentDtos.PaymentResponse(
            result.customerId(), result.received(), result.balanceBefore(), result.balanceAfter(),
            result.allocationMode(), allocations));
    }
}
