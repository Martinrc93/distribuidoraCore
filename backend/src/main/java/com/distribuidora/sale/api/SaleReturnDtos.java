package com.distribuidora.sale.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.distribuidora.sale.application.SaleReturnService;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class SaleReturnDtos {
    private SaleReturnDtos() { }

    public record ReturnRequest(
        @NotBlank @Size(max = 500) String reason,
        @NotEmpty List<@NotNull @Valid ReturnItemRequest> items
    ) implements SaleReturnService.ReturnCommand { }

    public record ReturnItemRequest(
        @NotNull UUID saleItemId,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity
    ) implements SaleReturnService.ReturnItemCommand { }

    public record ReturnItemResponse(UUID saleItemId, UUID productId, BigDecimal quantity) { }

    public record ReturnResponse(UUID returnId, UUID saleId, String reason, List<ReturnItemResponse> items) { }
}
