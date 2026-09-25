package com.distribuidora.notification.application;

/** In-process handoff from the durable outbox to idempotent application consumers. */
public record OutboxDispatchEvent(OutboxEvent event) {
}
