package com.distribuidora.notification.infrastructure;

import com.distribuidora.notification.application.NotificationChannelSender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Component
public class WebhookNotificationSender implements NotificationChannelSender {
    private final ObjectMapper objectMapper;
    private final String emailUrl;
    private final String emailToken;
    private final String whatsappUrl;
    private final String whatsappToken;
    private final Duration timeout;
    private final HttpClient client;

    public WebhookNotificationSender(ObjectMapper objectMapper,
            @Value("${app.notifications.email.webhook-url:}") String emailUrl,
            @Value("${app.notifications.email.webhook-token:}") String emailToken,
            @Value("${app.notifications.whatsapp.webhook-url:}") String whatsappUrl,
            @Value("${app.notifications.whatsapp.webhook-token:}") String whatsappToken,
            @Value("${app.notifications.timeout-ms:10000}") long timeoutMs) {
        this.objectMapper = objectMapper;
        this.emailUrl = emailUrl;
        this.emailToken = emailToken;
        this.whatsappUrl = whatsappUrl;
        this.whatsappToken = whatsappToken;
        this.timeout = Duration.ofMillis(Math.max(500, Math.min(timeoutMs, 60000)));
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public boolean isConfigured(String channel) {
        return "EMAIL".equals(channel) ? validHttpUrl(emailUrl)
            : "WHATSAPP".equals(channel) && validHttpUrl(whatsappUrl);
    }

    @Override
    public void send(String channel, String recipient, String subject, String filename, byte[] pdf, UUID idempotencyKey) {
        String endpoint = "EMAIL".equals(channel) ? emailUrl : whatsappUrl;
        String token = "EMAIL".equals(channel) ? emailToken : whatsappToken;
        if (!isConfigured(channel)) throw new IllegalStateException("El canal de notificación no está configurado");
        Map<String, Object> payload = Map.of("channel", channel, "recipient", recipient, "subject", subject,
            "filename", filename, "contentType", "application/pdf", "attachmentBase64", Base64.getEncoder().encodeToString(pdf));
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey.toString())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
            HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("El proveedor de notificaciones respondió HTTP " + response.statusCode());
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No se pudo serializar la solicitud de notificación", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("El envío de notificación fue interrumpido", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("No se pudo conectar con el proveedor de notificaciones", exception);
        }
    }

    private boolean validHttpUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value);
            if (uri.getHost() == null) return false;
            if ("https".equalsIgnoreCase(uri.getScheme())) return true;
            return "http".equalsIgnoreCase(uri.getScheme())
                && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                    || "::1".equals(uri.getHost()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
