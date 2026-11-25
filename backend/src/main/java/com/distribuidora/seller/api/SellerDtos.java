package com.distribuidora.seller.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.distribuidora.seller.application.SellerCommandService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SellerDtos {
    private SellerDtos() {
    }

    public record CreateSellerRequest(
        @NotNull(message = "userId es obligatorio") UUID userId,
        @NotBlank(message = "displayName es obligatorio") @Size(max = 160) String displayName
    ) implements SellerCommandService.CreateSellerCommand {
    }

    public record UpdateSellerRequest(
        @NotBlank(message = "displayName es obligatorio") @Size(max = 160) String displayName
    ) implements SellerCommandService.UpdateSellerCommand {
    }

    public record SellerStatusRequest(
        @NotBlank(message = "status es obligatorio") @Pattern(regexp = "ACTIVE|INACTIVE") String status
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
        @Size(max = 1000) List<@NotNull UUID> customerIds,
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
        @NotNull(message = "orderIds es obligatorio") @Size(max = 1000) List<@NotNull UUID> orderIds,
        Boolean onlyPending
    ) implements SellerCommandService.ReassignOrdersCommand {
    }

    public record ReassignOrdersResponse(
        UUID targetSellerId,
        int reassignedOrdersCount
    ) {
    }
}
