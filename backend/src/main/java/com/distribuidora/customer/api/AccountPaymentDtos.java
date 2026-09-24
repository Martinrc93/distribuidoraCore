package com.distribuidora.customer.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.distribuidora.customer.application.AccountPaymentService;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class AccountPaymentDtos {
    private AccountPaymentDtos() { }

    public record PaymentRequest(
        @NotNull @DecimalMin(value = "0.0000", inclusive = false) @Digits(integer = 15, fraction = 4)
        BigDecimal amount,
        @NotBlank @Pattern(regexp = "CASH|BANK_TRANSFER") String method,
        @Size(max = 100) String transferReference,
        UUID saleId
    ) implements AccountPaymentService.PaymentCommand { }

    public record Allocation(UUID saleId, String saleNumber, UUID paymentId, BigDecimal amount) { }

    public record PaymentResponse(UUID customerId, BigDecimal received, BigDecimal balanceBefore,
                                  BigDecimal balanceAfter, String allocationMode, List<Allocation> allocations) {
        public PaymentResponse { allocations = List.copyOf(allocations); }
    }
}
