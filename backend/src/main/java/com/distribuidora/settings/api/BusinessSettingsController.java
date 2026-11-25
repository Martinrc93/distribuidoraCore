package com.distribuidora.settings.api;

import com.distribuidora.settings.application.BusinessLogoImage;
import com.distribuidora.settings.application.BusinessProfile;
import com.distribuidora.settings.application.BusinessSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@RestController
@RequestMapping("/api/settings")
public class BusinessSettingsController {
    public record BusinessProfileRequest(
        @NotNull @Size(max = 120) String name,
        @NotNull @Size(max = 240) String address,
        @NotNull @Size(max = 60) String phone
    ) { }

    public record BusinessLogoRequest(@NotBlank @Size(max = BusinessLogoImage.MAX_ENCODED_LENGTH) String content) { }

    public record BusinessLogoResponse(byte[] content) { }

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

    @GetMapping("/business-logo")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<BusinessLogoResponse> businessLogo() {
        byte[] logo = service.businessLogo();
        String etag = "empty";
        if (logo != null) {
            try {
                etag = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(logo));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noCache().cachePrivate())
            .eTag(etag).body(new BusinessLogoResponse(logo));
    }

    @PutMapping("/business-logo")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public BusinessLogoResponse updateBusinessLogo(@Valid @RequestBody BusinessLogoRequest request) {
        return new BusinessLogoResponse(service.updateBusinessLogo(request.content()));
    }

    @DeleteMapping("/business-logo")
    @PreAuthorize("hasAuthority('ADMIN_ALL')")
    public ResponseEntity<Void> removeBusinessLogo() {
        service.removeBusinessLogo();
        return ResponseEntity.noContent().build();
    }

    private static BusinessSettingsDtos.CreditLimitResponse toResponse(BusinessSettingsService.CreditLimitResult result) {
        return new BusinessSettingsDtos.CreditLimitResponse(result.creditLimit(), result.enabled(),
            result.updatedAt(), result.updatedBy());
    }
}
