package com.distribuidora.seller.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.distribuidora.seller.application.SellerCommandService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SellerDtos {
    private SellerDtos() {
    }

    public record CreateSellerRequest(
        @NotNull(message = "userId es obligatorio") UUID userId,
        @NotBlank(message = "displayName es obligatorio") String displayName
    ) implements SellerCommandService.CreateSellerCommand {
    }

    public record UpdateSellerRequest(
        @NotBlank(message = "displayName es obligatorio") String displayName
    ) implements SellerCommandService.UpdateSellerCommand {
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

    public record ReassignCustomersRequest(
        @NotNull(message = "sourceSellerId es obligatorio") UUID sourceSellerId,
        @NotNull(message = "targetSellerId es obligatorio") UUID targetSellerId,
        List<UUID> customerIds,
        Boolean reassignPendingOrders
    ) implements SellerCommandService.ReassignCustomersCommand {
    }

    public record ReassignCustomersResponse(
        UUID sourceSellerId,
        UUID targetSellerId,
        int reassignedCustomersCount,
        int reassignedOrdersCount
    ) {
    }

    public record ReassignOrdersRequest(
        @NotNull(message = "targetSellerId es obligatorio") UUID targetSellerId,
        @NotNull(message = "orderIds es obligatorio") List<UUID> orderIds,
        Boolean onlyPending
    ) implements SellerCommandService.ReassignOrdersCommand {
    }

    public record ReassignOrdersResponse(
        UUID targetSellerId,
        int reassignedOrdersCount
    ) {
    }
}
