package com.distribuidora.notification.application;

import java.util.UUID;

public record OutboxEvent(UUID id, String eventType, String aggregateType, UUID aggregateId,
                          String payload, String idempotencyKey, int attemptCount) {
}
