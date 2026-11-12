package com.distribuidora.pricing.api;

import com.distribuidora.pricing.application.PriceListFollowService;
import com.distribuidora.pricing.application.PricingCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/pricing/lists")
public class PricingCommandController {
    private final PricingCommandService service;

    public PricingCommandController(PricingCommandService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<IdResponse> createList(@Valid @NotNull @RequestBody CreateListRequest request) {
        return ResponseEntity.status(201).body(new IdResponse(service.createList(request.code(), request.name())));
    }

    @GetMapping("/creation-preview")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public Map<String, Object> creationPreview() {
        return service.creationPreview();
    }

    @PostMapping("/{listId}/reviews/confirm")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ConfirmReviewResponse confirmReview(@PathVariable UUID listId, @Valid @RequestBody(required = false) ConfirmReviewRequest request) {
        return new ConfirmReviewResponse(service.confirmFollowingPrices(listId, request == null ? null : request.productIds()));
    }

    /** Lists that follow the default price of the product and still show a warning before it changes. */
    @GetMapping("/products/{productId}/followers")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public List<PriceListFollowService.Follower> followers(@PathVariable UUID productId) {
        return service.unacknowledgedFollowers(productId);
    }

    @PutMapping("/{listId}")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> renameList(@PathVariable UUID listId, @Valid @NotNull @RequestBody RenameListRequest request) {
        service.renameList(listId, request.name());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{listId}/status")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> setListStatus(@PathVariable UUID listId, @Valid @NotNull @RequestBody StatusRequest request) {
        service.setListStatus(listId, request.status());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{listId}/products/{productId}")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> setProductPrice(
        @PathVariable UUID listId,
        @PathVariable UUID productId,
        @Valid @NotNull @RequestBody ProductPriceRequest request
    ) {
        service.setProductPrice(listId, productId, request.price(), request.effectiveOn(), request.acknowledgesFollowers());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{listId}/products/{productId}/history/{effectiveOn}")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> cancelScheduledPrice(
        @PathVariable UUID listId,
        @PathVariable UUID productId,
        @PathVariable LocalDate effectiveOn
    ) {
        service.cancelScheduledPrice(listId, productId, effectiveOn);
        return ResponseEntity.noContent().build();
    }

    public record CreateListRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 120) String name
    ) { }

    public record RenameListRequest(@NotBlank @Size(max = 120) String name) { }

    public record StatusRequest(@NotBlank String status) { }

    /** {@code acknowledgeFollowers=true} means the user accepted the warning about lists following the default one. */
    public record ProductPriceRequest(
        @NotNull @DecimalMin("0.0000") @Digits(integer = 15, fraction = 4) BigDecimal price,
        LocalDate effectiveOn,
        Boolean acknowledgeFollowers
    ) {
        public ProductPriceRequest(BigDecimal price) { this(price, null, null); }

        public ProductPriceRequest(BigDecimal price, LocalDate effectiveOn) { this(price, effectiveOn, null); }

        public boolean acknowledgesFollowers() { return Boolean.TRUE.equals(acknowledgeFollowers); }
    }

    /** Without product ids, every pending price of the list is confirmed. */
    public record ConfirmReviewRequest(@Size(max = 1000) List<UUID> productIds) { }

    public record ConfirmReviewResponse(int confirmed) { }

    public record IdResponse(UUID id) { }
}
