package com.distribuidora.settings.api;

import com.distribuidora.settings.application.BusinessSettingsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/credit-limit")
public class BusinessSettingsController {
    private final BusinessSettingsService service;

    public BusinessSettingsController(BusinessSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public BusinessSettingsDtos.CreditLimitResponse creditLimit() {
        return toResponse(service.creditLimit());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<BusinessSettingsDtos.CreditLimitResponse> updateCreditLimit(
        @Valid @RequestBody BusinessSettingsDtos.CreditLimitRequest request) {
        return ResponseEntity.ok(toResponse(service.updateCreditLimit(request)));
    }

    private static BusinessSettingsDtos.CreditLimitResponse toResponse(BusinessSettingsService.CreditLimitResult result) {
        return new BusinessSettingsDtos.CreditLimitResponse(result.creditLimit(), result.enabled(),
            result.updatedAt(), result.updatedBy());
    }
}
