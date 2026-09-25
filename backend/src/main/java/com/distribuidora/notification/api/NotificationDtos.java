package com.distribuidora.notification.api;

import java.util.UUID;
import java.time.Instant;

public final class NotificationDtos {
    private NotificationDtos() {}

    public record CreateRequest(String channel, String recipient, String format, String idempotencyKey) {}
    public record CreateResponse(UUID requestId, String status) {}
    public record StatusResponse(UUID requestId, String status, int attemptCount,
                                Instant requestedAt, Instant sentAt, String lastError) {}
}
