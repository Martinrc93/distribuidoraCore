package com.distribuidora.order.api;

import com.distribuidora.order.application.OrderConfirmationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class OrderConfirmationDtos {
    private OrderConfirmationDtos() {
    }

    public record ConfirmationRequest(
        @NotBlank @Size(max = 100) String idempotencyKey,
        @NotNull UUID customerId,
        UUID priceListId,
        @NotNull @Size(min = 1) List<@NotNull @Valid LineRequest> lines,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4)
        BigDecimal orderDiscountPercent,
        List<@NotNull @Valid PaymentRequest> payments,
        UUID sellerId
    ) implements OrderConfirmationService.ConfirmationCommand {
        public ConfirmationRequest(String idempotencyKey, UUID customerId, UUID priceListId,
                                   List<LineRequest> lines, BigDecimal orderDiscountPercent,
                                   List<PaymentRequest> payments) {
            this(idempotencyKey, customerId, priceListId, lines, orderDiscountPercent, payments, null);
        }
    }

    public record LineRequest(
        @NotNull UUID productId,
        @NotNull @DecimalMin(value = "0.0000", inclusive = false) @Digits(integer = 15, fraction = 4)
        BigDecimal quantity,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4)
        BigDecimal lineDiscountPercent,
        @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal unitPriceOverride
    ) implements OrderConfirmationService.LineCommand {
    }

    public record PaymentRequest(
        @NotBlank @Pattern(regexp = "CASH|BANK_TRANSFER|CUSTOMER_ACCOUNT") String method,
        @NotNull @DecimalMin(value = "0.0000", inclusive = false) @Digits(integer = 15, fraction = 4)
        BigDecimal amount
    ) implements OrderConfirmationService.PaymentCommand {
    }

    public record ConfirmationResponse(
        UUID orderId,
        UUID saleId,
        String orderNumber,
        String saleNumber,
        BigDecimal total,
        BigDecimal paid,
        BigDecimal balance,
        CreditLimitWarning creditLimitWarning
    ) {
        public ConfirmationResponse(UUID orderId, UUID saleId, String orderNumber, String saleNumber,
                                    BigDecimal total, BigDecimal paid, BigDecimal balance) {
            this(orderId, saleId, orderNumber, saleNumber, total, paid, balance, null);
        }
    }

    public record CreditLimitWarning(BigDecimal creditLimit, BigDecimal projectedBalance, BigDecimal exceededBy) {
    }
}
