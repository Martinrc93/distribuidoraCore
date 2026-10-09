package com.distribuidora.identity;

import com.distribuidora.identity.application.InvitationEmailUnavailableException;
import com.distribuidora.identity.application.UserInvitationEmailService;
import com.distribuidora.notification.application.NotificationChannelSender;
import com.distribuidora.notification.application.OutboxDispatchEvent;
import com.distribuidora.notification.application.OutboxEvent;
import com.distribuidora.notification.application.OutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserInvitationEmailServiceTest {
    private final NotificationChannelSender sender = mock(NotificationChannelSender.class);
    private final OutboxService outbox = mock(OutboxService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final UserInvitationEmailService service = new UserInvitationEmailService(sender, outbox, mapper, "https://app.example.test/");

    @Test
    void queuesPublicActivationLinkAndExpiration() {
        when(sender.isConfigured("EMAIL")).thenReturn(true);
        UUID userId = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(1800);
        service.enqueue(userId, "seller@example.test", "secret-token", expiry);
        verify(outbox).enqueue(UserInvitationEmailService.EVENT_TYPE, "USER", userId,
            Map.of("email", "seller@example.test", "activationLink", "https://app.example.test/activate?token=secret-token",
                "expiresAt", expiry.toString()), "USER_INVITATION_EMAIL:" + userId);
        verify(sender, never()).sendEmail(anyString(), anyString(), anyString(), any());
    }

    @Test
    void sendsActivationLinkWithStableKeyAndPropagatesProviderFailure() throws Exception {
        var event = event(Instant.now().plusSeconds(1800));
        doThrow(new IllegalStateException("Provider unavailable")).doNothing().when(sender)
            .sendEmail(eq("seller@example.test"), anyString(), anyString(), eq(event.id()));
        assertThatThrownBy(() -> service.dispatch(new OutboxDispatchEvent(event)))
            .hasMessage("Provider unavailable");
        service.dispatch(new OutboxDispatchEvent(event));
        verify(sender, times(2)).sendEmail(eq("seller@example.test"), eq("Activar cuenta en Distribuidora"),
            contains("https://app.example.test/activate?token=secret-token"), eq(event.id()));
    }

    @Test
    void refusesExpiredInvitationsAndIgnoresOtherEvents() throws Exception {
        assertThatThrownBy(() -> service.dispatch(new OutboxDispatchEvent(event(Instant.now().minusSeconds(1)))))
            .hasMessageContaining("venció");
        service.dispatch(new OutboxDispatchEvent(new OutboxEvent(UUID.randomUUID(), "OTHER", "USER",
            UUID.randomUUID(), "{}", "other", 1)));
        verifyNoInteractions(sender);
    }

    @Test
    void refusesMissingEmailProviderOrUnsafePublicUrl() {
        assertThatThrownBy(service::requireConfigured).isInstanceOf(InvitationEmailUnavailableException.class);
        when(sender.isConfigured("EMAIL")).thenReturn(true);
        for (String url : new String[]{"", "http://app.example.test", "https://user:pass@app.example.test", "https://app.example.test/?redirect=evil", "https://app.example.test/#fragment"}) {
            assertThatThrownBy(new UserInvitationEmailService(sender, outbox, mapper, url)::requireConfigured)
                .isInstanceOf(InvitationEmailUnavailableException.class);
        }
        new UserInvitationEmailService(sender, outbox, mapper, "http://localhost:5173").requireConfigured();
    }

    private OutboxEvent event(Instant expiresAt) throws Exception {
        return new OutboxEvent(UUID.randomUUID(), UserInvitationEmailService.EVENT_TYPE, "USER", UUID.randomUUID(),
            mapper.writeValueAsString(Map.of("email", "seller@example.test", "activationLink", "https://app.example.test/activate?token=secret-token",
                "expiresAt", expiresAt.toString())), "invitation", 1);
    }
}
