package com.distribuidora.notification.api;

import java.util.UUID;
import java.time.Instant;
import com.distribuidora.notification.application.NotificationRequestService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class NotificationDtos {
    private NotificationDtos() {}

    public record CreateRequest(
        @NotBlank @Size(max = 20) String channel,
        @NotBlank @Size(max = 320) String recipient,
        @NotBlank @Size(max = 20) String format,
        @NotBlank @Size(max = 120) String idempotencyKey
    ) implements NotificationRequestService.CreateNotificationCommand {}
    public record CreateResponse(UUID requestId, String status) {}
    public record StatusResponse(UUID requestId, String status, int attemptCount,
                                Instant requestedAt, Instant sentAt, String lastError) {}
}
