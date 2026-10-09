package com.distribuidora.identity.application;

import com.distribuidora.notification.application.NotificationChannelSender;
import com.distribuidora.notification.application.OutboxDispatchEvent;
import com.distribuidora.notification.application.OutboxEvent;
import com.distribuidora.notification.application.OutboxService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class UserInvitationEmailService {
    public static final String EVENT_TYPE = "USER_INVITATION_EMAIL_REQUESTED";
    private final NotificationChannelSender sender;
    private final OutboxService outbox;
    private final ObjectMapper objectMapper;
    private final String publicUrl;

    public UserInvitationEmailService(NotificationChannelSender sender, OutboxService outbox,
            ObjectMapper objectMapper, @Value("${app.public-url:}") String publicUrl) {
        this.sender = sender;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.publicUrl = publicUrl == null ? "" : publicUrl.trim().replaceAll("/+$", "");
    }

    public void requireConfigured() {
        if (!sender.isConfigured("EMAIL") || !validPublicUrl()) throw new InvitationEmailUnavailableException();
    }

    public void enqueue(UUID userId, String email, String rawToken, Instant expiresAt) {
        requireConfigured();
        outbox.enqueue(EVENT_TYPE, "USER", userId,
            Map.of("email", email, "activationLink", publicUrl + "/activate?token=" + rawToken,
                "expiresAt", expiresAt.toString()), "USER_INVITATION_EMAIL:" + userId);
    }

    @EventListener
    public void dispatch(OutboxDispatchEvent dispatchEvent) {
        OutboxEvent event = dispatchEvent.event();
        if (!EVENT_TYPE.equals(event.eventType())) return;
        try {
            var payload = objectMapper.readTree(event.payload());
            if (!Instant.parse(payload.required("expiresAt").asText()).isAfter(Instant.now())) {
                throw new IllegalStateException("La invitación venció antes de completar el envío");
            }
            sender.sendEmail(payload.required("email").asText(), "Activar cuenta en Distribuidora",
                "Se creó una cuenta para usted en Distribuidora.\n\n"
                    + "Para activar su cuenta y elegir una contraseña, abra este enlace:\n"
                    + payload.required("activationLink").asText()
                    + "\n\nEl enlace es de un solo uso y vence 30 minutos después de crear la cuenta.", event.id());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("No se pudo leer la solicitud de invitación", exception);
        }
    }

    private boolean validPublicUrl() {
        try {
            URI uri = URI.create(publicUrl);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !uri.getPath().isEmpty()) return false;
            return "https".equalsIgnoreCase(uri.getScheme())
                || ("http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
