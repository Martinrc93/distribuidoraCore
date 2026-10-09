package com.distribuidora.notification;

import com.distribuidora.notification.infrastructure.WebhookNotificationSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookNotificationSenderTest {
    @Test
    void postsAttachmentAndStableIdempotencyKeyToConfiguredProvider() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/email", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        try {
            WebhookNotificationSender sender = new WebhookNotificationSender(new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/email", "email-secret",
                "", "", 2000);
            UUID eventId = UUID.randomUUID();
            sender.send("EMAIL", "sales@example.test", "Ticket", "ticket.pdf", "%PDF".getBytes(), eventId);
            assertThat(sender.isConfigured("EMAIL")).isTrue();
            assertThat(key.get()).isEqualTo(eventId.toString());
            assertThat(authorization.get()).isEqualTo("Bearer email-secret");
            assertThat(body.get()).contains("sales@example.test", "ticket.pdf", "attachmentBase64");

            sender.sendEmail("invited@example.test", "Activar cuenta", "https://app.example.test/activate?token=secret", eventId);
            var payload = new ObjectMapper().readTree(body.get());
            assertThat(payload.get("recipient").asText()).isEqualTo("invited@example.test");
            assertThat(payload.get("text").asText()).isEqualTo("https://app.example.test/activate?token=secret");
            assertThat(payload.get("contentType").asText()).isEqualTo("text/plain");
            assertThat(payload.has("attachmentBase64")).isFalse();
            assertThat(key.get()).isEqualTo(eventId.toString());
            assertThat(authorization.get()).isEqualTo("Bearer email-secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUnconfiguredChannelsAndProviderErrors() {
        WebhookNotificationSender sender = new WebhookNotificationSender(new ObjectMapper(), "", "", "invalid", "", 10000);
        assertThat(sender.isConfigured("EMAIL")).isFalse();
        assertThat(sender.isConfigured("WHATSAPP")).isFalse();
        assertThatThrownBy(() -> sender.send("EMAIL", "a@example.test", "subject", "ticket.pdf", new byte[1], UUID.randomUUID()))
            .isInstanceOf(IllegalStateException.class);
        WebhookNotificationSender insecure = new WebhookNotificationSender(new ObjectMapper(),
            "http://provider.example/send", "token", "", "", 10000);
        assertThat(insecure.isConfigured("EMAIL")).isFalse();
        assertThatThrownBy(() -> sender.sendEmail("a@example.test", "subject", "text", UUID.randomUUID()))
            .isInstanceOf(IllegalStateException.class);
    }
}
