package com.distribuidora.inventory.api;

import com.distribuidora.inventory.application.InventoryCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/inventory")
public class InventoryCommandController {
    private final InventoryCommandService service;

    public InventoryCommandController(InventoryCommandService service) {
        this.service = service;
    }

    @PostMapping("/{productId}/adjustments")
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    public ResponseEntity<Void> adjust(@PathVariable UUID productId, @Valid @RequestBody AdjustmentRequest request) {
        service.adjust(request.depotId(), productId, request.quantity(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/transfers")
    @PreAuthorize("hasAuthority('STOCK_ADJUST')")
    public ResponseEntity<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        UUID transferId = service.transfer(request.fromDepotId(), request.toDepotId(), request.productId(),
            request.quantity(), request.reason());
        return ResponseEntity.status(201).body(new TransferResponse(transferId));
    }

    public record AdjustmentRequest(
        @NotNull BigDecimal quantity,
        @NotBlank @Size(max = 500) String reason,
        UUID depotId
    ) {
        public AdjustmentRequest(BigDecimal quantity, String reason) {
            this(quantity, reason, null);
        }
    }

    public record TransferRequest(
        @NotNull UUID fromDepotId,
        @NotNull UUID toDepotId,
        @NotNull UUID productId,
        @NotNull @jakarta.validation.constraints.DecimalMin(value = "0.0001", inclusive = true)
            @jakarta.validation.constraints.Digits(integer = 15, fraction = 4) BigDecimal quantity,
        @NotBlank @Size(max = 500) String reason
    ) { }

    public record TransferResponse(UUID transferId) { }
}
