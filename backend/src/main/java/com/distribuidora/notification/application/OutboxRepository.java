package com.distribuidora.notification.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimBatch(int batchSize, int maxAttempts, Duration lease) {
        jdbc.update("update notification.outbox_events set status = 'RETRY_EXHAUSTED', locked_at = null, "
                + "last_error = coalesce(last_error, 'Processing lease expired after maximum attempts'), updated_at = now() "
                + "where status = 'PROCESSING' and locked_at < now() - (? * interval '1 millisecond') and attempt_count >= ?",
            lease.toMillis(), maxAttempts);
        return jdbc.query("with candidates as ("
                + " select id from notification.outbox_events "
                + " where attempt_count < ? and ((status = 'PENDING' and available_at <= now()) "
                + " or (status = 'PROCESSING' and locked_at < now() - (? * interval '1 millisecond'))) "
                + " order by available_at, created_at limit ? for update skip locked"
                + ") update notification.outbox_events e set status = 'PROCESSING', "
                + "attempt_count = e.attempt_count + 1, locked_at = now(), updated_at = now() "
                + "from candidates c where e.id = c.id "
                + "returning e.id, e.event_type, e.aggregate_type, e.aggregate_id, e.payload::text, e.idempotency_key, e.attempt_count",
            (rs, rowNum) -> mapEvent(rs), maxAttempts, lease.toMillis(), batchSize);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(UUID eventId) {
        jdbc.update("update notification.outbox_events set status = 'PROCESSED', processed_at = now(), "
                + "locked_at = null, last_error = null, updated_at = now() where id = ? and status = 'PROCESSING'",
            eventId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(OutboxEvent event, int maxAttempts, Duration retryDelay, String error) {
        jdbc.update("update notification.outbox_events set status = ?, available_at = now() + (? * interval '1 millisecond'), "
                + "locked_at = null, last_error = ?, updated_at = now() where id = ? and status = 'PROCESSING'",
            event.attemptCount() >= maxAttempts ? "RETRY_EXHAUSTED" : "PENDING",
            retryDelay.toMillis(), abbreviate(error), event.id());
        return event.attemptCount() >= maxAttempts;
    }

    public long pendingCount() {
        Long count = jdbc.queryForObject("select count(*) from notification.outbox_events where status in ('PENDING', 'PROCESSING')",
            Long.class);
        return count == null ? 0 : count;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeTerminalBefore(Instant cutoff) {
        return jdbc.update("delete from notification.outbox_events where created_at < ? "
                + "and status in ('PROCESSED', 'RETRY_EXHAUSTED')", java.sql.Timestamp.from(cutoff));
    }

    private static OutboxEvent mapEvent(ResultSet rs) throws SQLException {
        return new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("event_type"),
            rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class),
            rs.getString("payload"), rs.getString("idempotency_key"), rs.getInt("attempt_count"));
    }

    private static String abbreviate(String error) {
        if (error == null || error.isBlank()) return "Unknown outbox handler error";
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }
}
