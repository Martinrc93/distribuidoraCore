package com.distribuidora.seller.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public final class SellerDtos {
    private SellerDtos() {
    }

    public record CreateSellerRequest(
        @NotNull(message = "userId es obligatorio") UUID userId,
        @NotBlank(message = "displayName es obligatorio") String displayName
    ) {
    }

    public record UpdateSellerRequest(
        @NotBlank(message = "displayName es obligatorio") String displayName
    ) {
    }

    public record SellerStatusRequest(
        @NotBlank(message = "status es obligatorio") String status
    ) {
    }

    public record IdResponse(UUID id) {
    }

    public record SellerResponse(
        UUID id,
        UUID userId,
        String displayName,
        String email,
        String status,
        Instant createdAt,
        long assignedCustomersCount
    ) {
    }
}
