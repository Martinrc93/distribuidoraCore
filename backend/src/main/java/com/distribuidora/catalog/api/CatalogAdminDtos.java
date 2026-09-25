package com.distribuidora.catalog.api;

import jakarta.validation.constraints.NotBlank;
import com.distribuidora.catalog.application.BrandService;
import com.distribuidora.catalog.application.CategoryService;

import java.time.Instant;
import java.util.UUID;

public final class CatalogAdminDtos {
    private CatalogAdminDtos() {
    }

    public record CreateBrandRequest(
        @NotBlank(message = "name es obligatorio") String name,
        String code
    ) implements BrandService.BrandCommand {
    }

    public record UpdateBrandRequest(
        @NotBlank(message = "name es obligatorio") String name,
        String code
    ) implements BrandService.BrandCommand {
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
    ) implements CategoryService.CategoryCommand {
    }

    public record UpdateCategoryRequest(
        @NotBlank(message = "name es obligatorio") String name,
        String code
    ) implements CategoryService.CategoryCommand {
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
