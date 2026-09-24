package com.distribuidora.notification.application;

import java.util.UUID;

public interface NotificationChannelSender {
    boolean isConfigured(String channel);

    void send(String channel, String recipient, String subject, String filename,
              byte[] pdf, UUID idempotencyKey);
}
