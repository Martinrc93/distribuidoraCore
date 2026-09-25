package com.distribuidora.notification.application;

public record OutboxRetryExhaustedEvent(OutboxEvent event) {
}
