package com.distribuidora.notification.application;

import com.distribuidora.audit.application.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.time.Instant;

@Service
public class NotificationRequestStateService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public NotificationRequestStateService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markAttemptStarted(UUID requestId, OutboxEvent event) {
        int updated = jdbc.update("update notification.delivery_requests set status = 'SENDING', "
                + "attempt_count = attempt_count + 1, last_error = null, updated_at = now() "
                + "where id = ? and status <> 'SENT'", requestId);
        if (updated == 0) return false;
        Integer count = jdbc.queryForObject("select attempt_count from notification.delivery_requests where id = ?", Integer.class, requestId);
        audit.recordWithinTransaction(null, "NOTIFICATION_SEND_ATTEMPT", "NOTIFICATION", requestId.toString(), "SUCCESS",
            Map.of("outboxEventId", event.id().toString(), "attempt", count));
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(UUID requestId, OutboxEvent event) {
        jdbc.update("update notification.delivery_requests set status = 'SENT', sent_at = now(), last_error = null, updated_at = now() where id = ?",
            requestId);
        audit.recordWithinTransaction(null, "NOTIFICATION_SENT", "NOTIFICATION", requestId.toString(), "SUCCESS",
            Map.of("outboxEventId", event.id().toString(), "attempt", event.attemptCount()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID requestId, OutboxEvent event, RuntimeException exception) {
        String error = exception.getClass().getSimpleName();
        jdbc.update("update notification.delivery_requests set status = 'FAILED', last_error = ?, updated_at = now() where id = ? and status <> 'SENT'",
            error, requestId);
        audit.recordWithinTransaction(null, "NOTIFICATION_SEND_FAILED", "NOTIFICATION", requestId.toString(), "FAILURE",
            Map.of("outboxEventId", event.id().toString(), "attempt", event.attemptCount(), "errorType", error));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markExhausted(OutboxEvent event) {
        jdbc.update("update notification.delivery_requests set status = 'RETRY_EXHAUSTED', updated_at = now() where id = ? and status <> 'SENT'",
            event.aggregateId());
        audit.recordWithinTransaction(null, "NOTIFICATION_RETRY_EXHAUSTED", "NOTIFICATION", event.aggregateId().toString(), "FAILURE",
            Map.of("outboxEventId", event.id().toString(), "attempt", event.attemptCount()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeTerminalBefore(Instant cutoff) {
        return jdbc.update("delete from notification.delivery_requests where created_at < ? "
                + "and status in ('SENT', 'FAILED', 'RETRY_EXHAUSTED')", java.sql.Timestamp.from(cutoff));
    }
}
