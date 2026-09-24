package com.distribuidora.settings.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class BusinessSettingsDtos {
    private BusinessSettingsDtos() { }

    public record CreditLimitRequest(
        @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal creditLimit
    ) { }

    public record CreditLimitResponse(BigDecimal creditLimit, boolean enabled, Instant updatedAt, UUID updatedBy) { }
}
