package com.distribuidora.settings.api;

import com.distribuidora.settings.application.BusinessProfile;
import com.distribuidora.settings.application.BusinessSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class BusinessSettingsController {
    public record BusinessProfileRequest(
        @NotNull @Size(max = 120) String name,
        @NotNull @Size(max = 240) String address,
        @NotNull @Size(max = 60) String phone
    ) { }

    private final BusinessSettingsService service;

    public BusinessSettingsController(BusinessSettingsService service) {
        this.service = service;
    }

    @GetMapping("/credit-limit")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public BusinessSettingsDtos.CreditLimitResponse creditLimit() {
        return toResponse(service.creditLimit());
    }

    @PutMapping("/credit-limit")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<BusinessSettingsDtos.CreditLimitResponse> updateCreditLimit(
        @Valid @RequestBody BusinessSettingsDtos.CreditLimitRequest request) {
        return ResponseEntity.ok(toResponse(service.updateCreditLimit(request)));
    }

    @GetMapping("/business-profile")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public BusinessProfile businessProfile() {
        return service.businessProfile();
    }

    @PutMapping("/business-profile")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public BusinessProfile updateBusinessProfile(@Valid @RequestBody BusinessProfileRequest request) {
        return service.updateBusinessProfile(new BusinessProfile(request.name(), request.address(), request.phone()));
    }

    private static BusinessSettingsDtos.CreditLimitResponse toResponse(BusinessSettingsService.CreditLimitResult result) {
        return new BusinessSettingsDtos.CreditLimitResponse(result.creditLimit(), result.enabled(),
            result.updatedAt(), result.updatedBy());
    }
}
