package com.distribuidora.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import com.distribuidora.order.api.OrderConfirmationDtos.LineRequest;
import com.distribuidora.order.application.OrderConfirmationService;

public final class OrderEditDtos {
    private OrderEditDtos() {
    }

    public record EditRequest(
        UUID priceListId,
        @NotNull @Size(min = 1) List<@NotNull @Valid LineRequest> lines,
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4)
        BigDecimal orderDiscountPercent
    ) implements OrderConfirmationService.EditCommand {
    }

    public record EditResponse(UUID orderId, UUID saleId, BigDecimal total, BigDecimal paid, BigDecimal balance) {
    }
}
