package com.distribuidora.catalog.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.distribuidora.catalog.application.BrandService;
import com.distribuidora.catalog.application.CategoryService;

import java.time.Instant;
import java.util.UUID;

public final class CatalogAdminDtos {
    private CatalogAdminDtos() {
    }

    public record CreateBrandRequest(
        @NotBlank(message = "name es obligatorio") @Size(max = 100) String name
    ) implements BrandService.BrandCommand {
    }

    public record UpdateBrandRequest(
        @NotBlank(message = "name es obligatorio") @Size(max = 100) String name
    ) implements BrandService.BrandCommand {
    }

    public record BrandResponse(
        UUID id,
        String name,
        String status,
        Instant createdAt,
        long productCount
    ) {
    }

    public record CreateCategoryRequest(
        @NotBlank(message = "name es obligatorio") @Size(max = 100) String name
    ) implements CategoryService.CategoryCommand {
    }

    public record UpdateCategoryRequest(
        @NotBlank(message = "name es obligatorio") @Size(max = 100) String name
    ) implements CategoryService.CategoryCommand {
    }

    public record CategoryResponse(
        UUID id,
        String name,
        String status,
        Instant createdAt,
        long productCount
    ) {
    }

    public record StatusRequest(
        @NotBlank(message = "status es obligatorio") @Pattern(regexp = "ACTIVE|INACTIVE") String status
    ) {
    }

    public record IdResponse(
        UUID id
    ) {
    }
}
