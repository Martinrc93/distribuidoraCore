package com.distribuidora.notification.application;

import com.distribuidora.audit.application.AuditService;
import com.distribuidora.document.application.SaleDocumentService;
import com.distribuidora.document.rendering.OpenPdfA4Renderer;
import com.distribuidora.document.rendering.OpenPdfTicketRenderer;
import com.distribuidora.document.rendering.SaleDocumentModel;
import com.distribuidora.shared.security.CurrentUserAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class NotificationRequestService {
    public interface CreateNotificationCommand {
        String channel();
        String recipient();
        String format();
        String idempotencyKey();
    }

    public record CreateNotificationResult(UUID requestId, String status) { }

    public record NotificationStatus(UUID requestId, String status, int attemptCount,
                                     java.time.Instant requestedAt, java.time.Instant sentAt, String lastError) { }

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final OutboxService outbox;
    private final CurrentUserAccess currentUser;
    private final NotificationChannelSender sender;
    private final SaleDocumentService documentService;
    private final OpenPdfA4Renderer a4Renderer;
    private final OpenPdfTicketRenderer ticketRenderer;
    private final NotificationRequestStateService stateService;

    public NotificationRequestService(JdbcTemplate jdbc, AuditService audit, OutboxService outbox,
            CurrentUserAccess currentUser, NotificationChannelSender sender, SaleDocumentService documentService,
            OpenPdfA4Renderer a4Renderer, OpenPdfTicketRenderer ticketRenderer,
            NotificationRequestStateService stateService) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.outbox = outbox;
        this.currentUser = currentUser;
        this.sender = sender;
        this.documentService = documentService;
        this.a4Renderer = a4Renderer;
        this.ticketRenderer = ticketRenderer;
        this.stateService = stateService;
    }

    @Transactional
    public CreateNotificationResult request(UUID orderId, CreateNotificationCommand request) {
        if (orderId == null || request == null) throw new IllegalArgumentException("La solicitud de notificación es obligatoria");
        currentUser.requireOrderAccess(orderId);
        String channel = normalize(request.channel());
        String format = normalize(request.format());
        String recipient = request.recipient() == null ? "" : request.recipient().trim();
        String idempotencyKey = request.idempotencyKey() == null ? "" : request.idempotencyKey().trim();
        if (!channel.equals("EMAIL") && !channel.equals("WHATSAPP")) throw new IllegalArgumentException("Canal inválido");
        if (!format.equals("A4") && !format.equals("TICKET")) throw new IllegalArgumentException("Formato inválido");
        if (channel.equals("EMAIL") && !EMAIL.matcher(recipient).matches()) throw new IllegalArgumentException("Email inválido");
        if (channel.equals("WHATSAPP") && !PHONE.matcher(recipient).matches()) throw new IllegalArgumentException("Teléfono WhatsApp inválido; use formato E.164");
        if (idempotencyKey.isBlank() || idempotencyKey.length() > 120) throw new IllegalArgumentException("idempotencyKey es obligatorio y admite hasta 120 caracteres");
        if (!sender.isConfigured(channel)) throw new NotificationChannelNotConfiguredException(channel);

        Map<String, Object> sale = jdbc.queryForMap("select s.id as sale_id from orders.orders o "
            + "join sale.sales s on s.order_id = o.id where o.id = ?", orderId);
        UUID saleId = uuid(sale.get("sale_id"));
        jdbc.update("insert into notification.delivery_requests(id, order_id, sale_id, channel, document_format, "
                + "recipient, requested_by, idempotency_key) values (?, ?, ?, ?, ?, ?, ?, ?) "
                + "on conflict (idempotency_key) do nothing",
            UUID.randomUUID(), orderId, saleId, channel, format, recipient, currentUser.userId(), idempotencyKey);
        Map<String, Object> existing = jdbc.queryForMap("select id, order_id, sale_id, channel, document_format, recipient, status "
            + "from notification.delivery_requests where idempotency_key = ?", idempotencyKey);
        if (!orderId.equals(uuid(existing.get("order_id"))) || !saleId.equals(uuid(existing.get("sale_id")))
            || !channel.equals(existing.get("channel")) || !format.equals(existing.get("document_format"))
            || !recipient.equals(existing.get("recipient"))) {
            throw new IllegalArgumentException("idempotencyKey ya fue usado con otra solicitud");
        }
        UUID requestId = uuid(existing.get("id"));
        if (!"QUEUED".equals(existing.get("status")) && jdbc.queryForObject(
                "select outbox_event_id is not null from notification.delivery_requests where id = ?", Boolean.class, requestId)) {
            return new CreateNotificationResult(requestId, (String) existing.get("status"));
        }
        UUID eventId = outbox.enqueue("NOTIFICATION_DELIVERY_REQUESTED", "NOTIFICATION", requestId,
            Map.of("notificationRequestId", requestId.toString()), "NOTIFICATION_REQUEST:" + requestId);
        jdbc.update("update notification.delivery_requests set outbox_event_id = ? where id = ? and outbox_event_id is null",
            eventId, requestId);
        if (jdbc.queryForObject("select count(*) from audit.audit_events where operation = 'NOTIFICATION_REQUEST' "
                + "and resource_id = ?", Long.class, requestId.toString()) == 0) {
            audit.recordWithinTransaction(currentUser.userId(), "NOTIFICATION_REQUEST", "NOTIFICATION", requestId.toString(),
                "SUCCESS", Map.of("channel", channel, "format", format, "recipient", mask(recipient)));
        }
        return new CreateNotificationResult(requestId, "QUEUED");
    }

    @Transactional(readOnly = true)
    public NotificationStatus status(UUID orderId, UUID requestId) {
        currentUser.requireOrderAccess(orderId);
        return jdbc.queryForObject("select id, status, attempt_count, created_at, sent_at, last_error "
                + "from notification.delivery_requests where id = ? and order_id = ?",
            (rs, rowNum) -> new NotificationStatus(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getInt("attempt_count"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(), rs.getString("last_error")),
            requestId, orderId);
    }

    @EventListener
    public void dispatch(OutboxDispatchEvent dispatchEvent) {
        OutboxEvent event = dispatchEvent.event();
        if (!"NOTIFICATION_DELIVERY_REQUESTED".equals(event.eventType())) return;
        UUID requestId = event.aggregateId();
        if (!stateService.markAttemptStarted(requestId, event)) return;
        try {
            DeliveryRequest request = load(requestId);
            SaleDocumentModel model = documentService.loadForDelivery(request.orderId());
            byte[] pdf = "A4".equals(request.format()) ? a4Renderer.render(model) : ticketRenderer.render(model);
            String filename = ("A4".equals(request.format()) ? "venta-" : "ticket-")
                + model.saleNumber().replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf";
            sender.send(request.channel(), request.recipient(), "Comprobante " + model.saleNumber(), filename, pdf, event.id());
            stateService.markSent(requestId, event);
        } catch (RuntimeException exception) {
            stateService.markFailed(requestId, event, exception);
            throw exception;
        }
    }

    @EventListener
    public void exhausted(OutboxRetryExhaustedEvent exhaustedEvent) {
        OutboxEvent event = exhaustedEvent.event();
        if (!"NOTIFICATION_DELIVERY_REQUESTED".equals(event.eventType())) return;
        stateService.markExhausted(event);
    }

    private DeliveryRequest load(UUID requestId) {
        return jdbc.queryForObject("select order_id, channel, document_format, recipient from notification.delivery_requests where id = ?",
            (rs, rowNum) -> new DeliveryRequest(rs.getObject("order_id", UUID.class), rs.getString("channel"),
                rs.getString("document_format"), rs.getString("recipient")), requestId);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String mask(String value) {
        if (value.contains("@")) return value.substring(0, 1) + "***" + value.substring(value.indexOf('@'));
        return "***" + value.substring(Math.max(0, value.length() - 4));
    }

    private UUID uuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    private record DeliveryRequest(UUID orderId, String channel, String format, String recipient) {}
}
