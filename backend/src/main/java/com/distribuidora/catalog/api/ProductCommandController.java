package com.distribuidora.catalog.api;

import com.distribuidora.catalog.application.ProductCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
public class ProductCommandController {
    private final ProductCommandService service;
    public ProductCommandController(ProductCommandService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<IdResponse> create(@Valid @RequestBody ProductPayload body) {
        return ResponseEntity.status(201).body(new IdResponse(service.create(body.input())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @RequestBody ProductPayload body) {
        service.update(id, body.input(), body.acknowledgesFollowers()); return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> status(@PathVariable UUID id, @Valid @RequestBody StatusPayload body) {
        service.setStatus(id, body.status()); return ResponseEntity.noContent().build();
    }

    public record ProductPricePayload(
        @NotNull UUID priceListId,
        @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal price
    ) { }

    public record ProductPayload(
        @NotBlank @Size(max = 200) String description,
        @Size(max = 100) String category,
        @Size(max = 120) String presentation,
        @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal cost,
        @Valid @Size(max = 200) List<ProductPricePayload> prices,
        @NotNull UUID categoryId,
        @NotNull UUID brandId,
        @Size(max = 200) List<@NotNull UUID> supplierIds,
        @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal initialStock,
        /** True when the user accepted the warning about lists following the default list price. */
        Boolean acknowledgeFollowers
    ) {
        public boolean acknowledgesFollowers() { return Boolean.TRUE.equals(acknowledgeFollowers); }

        public ProductCommandService.ProductInput input() {
            List<ProductCommandService.ProductPriceInput> priceInputs = prices != null
                ? prices.stream().map(p -> new ProductCommandService.ProductPriceInput(p.priceListId(), p.price())).toList()
                : List.of();
            return new ProductCommandService.ProductInput(description, category, presentation, cost, priceInputs, categoryId, brandId, supplierIds, initialStock);
        }
    }

    public record StatusPayload(@NotBlank @jakarta.validation.constraints.Pattern(regexp = "ACTIVE|INACTIVE") String status) { }
    public record IdResponse(UUID id) { }
}
