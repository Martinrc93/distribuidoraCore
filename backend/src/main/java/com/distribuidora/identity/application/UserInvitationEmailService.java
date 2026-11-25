package com.distribuidora.identity.application;

import com.distribuidora.notification.application.NonRetryableOutboxException;
import com.distribuidora.notification.application.NotificationChannelSender;
import com.distribuidora.notification.application.OutboxDispatchEvent;
import com.distribuidora.notification.application.OutboxEvent;
import com.distribuidora.notification.application.OutboxService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
public class UserInvitationEmailService {
    public static final String EVENT_TYPE = "USER_INVITATION_EMAIL_REQUESTED";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final NotificationChannelSender sender;
    private final OutboxService outbox;
    private final ObjectMapper objectMapper;
    private final String publicUrl;
    private final SecretKey encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public UserInvitationEmailService(NotificationChannelSender sender, OutboxService outbox,
            ObjectMapper objectMapper, @Value("${app.public-url:}") String publicUrl) {
        this(sender, outbox, objectMapper, publicUrl, "");
    }

    @Autowired
    public UserInvitationEmailService(NotificationChannelSender sender, OutboxService outbox,
            ObjectMapper objectMapper,
            @Value("${app.public-url:}") String publicUrl,
            @Value("${app.security.jwt-secret:}") String encryptionSecret) {
        this.sender = sender;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.publicUrl = publicUrl == null ? "" : publicUrl.trim().replaceAll("/+$", "");
        this.encryptionKey = deriveKey(encryptionSecret);
    }

    public void requireConfigured() {
        if (!sender.isConfigured("EMAIL") || !validPublicUrl()) throw new InvitationEmailUnavailableException();
    }

    public void enqueue(UUID userId, String email, String rawToken, Instant expiresAt) {
        requireConfigured();
        String activationLink = publicUrl + "/activate?token=" + rawToken;
        Map<String, String> payload = encryptionKey != null
            ? Map.of("email", email, "encryptedActivationLink", encryptLink(activationLink), "expiresAt", expiresAt.toString())
            : Map.of("email", email, "activationLink", activationLink, "expiresAt", expiresAt.toString());
        outbox.enqueue(EVENT_TYPE, "USER", userId, payload, "USER_INVITATION_EMAIL:" + userId);
    }

    @EventListener
    public void dispatch(OutboxDispatchEvent dispatchEvent) {
        OutboxEvent event = dispatchEvent.event();
        if (!EVENT_TYPE.equals(event.eventType())) return;
        try {
            var payload = objectMapper.readTree(event.payload());
            if (!Instant.parse(payload.required("expiresAt").asText()).isAfter(Instant.now())) {
                throw new NonRetryableOutboxException("La invitación venció antes de completar el envío");
            }
            String activationLink = payload.hasNonNull("encryptedActivationLink")
                ? decryptLink(payload.get("encryptedActivationLink").asText())
                : payload.required("activationLink").asText();
            sender.sendEmail(payload.required("email").asText(), "Activar cuenta en Distribuidora",
                "Se creó una cuenta para usted en Distribuidora.\n\n"
                    + "Para activar su cuenta y elegir una contraseña, abra este enlace:\n"
                    + activationLink
                    + "\n\nEl enlace es de un solo uso y vence 30 minutos después de crear la cuenta.", event.id());
        } catch (JsonProcessingException exception) {
            throw new NonRetryableOutboxException("No se pudo leer la solicitud de invitación", exception);
        }
    }

    private SecretKey deriveKey(String secret) {
        if (secret == null || secret.isBlank()) return null;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(("user-invitation-outbox:" + secret).getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("No se pudo inicializar el cifrado de invitaciones", exception);
        }
    }

    private String encryptLink(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("No se pudo cifrar el enlace de invitación", exception);
        }
    }

    private String decryptLink(String encoded) {
        if (encryptionKey == null) {
            throw new NonRetryableOutboxException("Falta la clave para descifrar el enlace de invitación");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            if (combined.length <= GCM_IV_BYTES) {
                throw new NonRetryableOutboxException("El enlace cifrado de invitación es inválido");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, combined, 0, GCM_IV_BYTES));
            byte[] plaintext = cipher.doFinal(combined, GCM_IV_BYTES, combined.length - GCM_IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new NonRetryableOutboxException("No se pudo descifrar el enlace de invitación", exception);
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
