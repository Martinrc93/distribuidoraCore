package com.distribuidora.pricing.api;

import com.distribuidora.pricing.application.CommercialDiscountRuleCommandService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/pricing/discount-rules")
public class CommercialDiscountRuleCommandController {
    private final CommercialDiscountRuleCommandService service;

    public CommercialDiscountRuleCommandController(CommercialDiscountRuleCommandService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<IdResponse> create(@Valid @NotNull @RequestBody RuleRequest request) {
        return ResponseEntity.status(201).body(new IdResponse(service.create(request.toInput())));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> update(@PathVariable UUID id, @Valid @NotNull @RequestBody RuleRequest request) {
        service.update(id, request.toInput());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> setStatus(@PathVariable UUID id, @Valid @NotNull @RequestBody StatusRequest request) {
        service.setStatus(id, request.status());
        return ResponseEntity.noContent().build();
    }

    public record RuleRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,40}") String code,
        @NotBlank @Size(max = 160) String description,
        @NotBlank @Pattern(regexp = "LINE|ORDER") String kind,
        @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 3, fraction = 4) BigDecimal percent,
        UUID customerId,
        UUID priceListId,
        UUID productId,
        LocalDate validFrom,
        LocalDate validUntil,
        Integer priority
    ) {
        CommercialDiscountRuleCommandService.RuleInput toInput() {
            return new CommercialDiscountRuleCommandService.RuleInput(code, description, kind, percent,
                customerId, priceListId, productId, validFrom, validUntil, priority);
        }
    }

    public record StatusRequest(@NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) { }

    public record IdResponse(UUID id) { }
}
