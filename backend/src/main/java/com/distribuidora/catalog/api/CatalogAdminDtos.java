package com.distribuidora.catalog.api;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

public final class CatalogAdminDtos {
    private CatalogAdminDtos() {
    }

    public record CreateBrandRequest(
        @NotBlank(message = "name es obligatorio") String name,
        String code
    ) {
    }

    public record UpdateBrandRequest(
        @NotBlank(message = "name es obligatorio") String name,
        String code
    ) {
    }

    public record BrandResponse(
        UUID id,
        String name,
        String code,
        String status,
        Instant createdAt,
        long productCount
    ) {
    }

    public record CreateCategoryRequest(
        @NotBlank(message = "name es obligatorio") String name,
        String code
    ) {
    }

    public record UpdateCategoryRequest(
        @NotBlank(message = "name es obligatorio") String name,
        String code
    ) {
    }

    public record CategoryResponse(
        UUID id,
        String name,
        String code,
        String status,
        Instant createdAt,
        long productCount
    ) {
    }

    public record StatusRequest(
        @NotBlank(message = "status es obligatorio") String status
    ) {
    }

    public record IdResponse(
        UUID id
    ) {
    }
}
