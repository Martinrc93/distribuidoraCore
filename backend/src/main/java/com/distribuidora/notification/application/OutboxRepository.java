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
    private static final int PURGE_BATCH_SIZE = 5000;

    public record ClaimResult(List<OutboxEvent> claimed, List<OutboxEvent> expiredExhausted) {
        public ClaimResult {
            claimed = List.copyOf(claimed);
            expiredExhausted = List.copyOf(expiredExhausted);
        }
    }

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> claimBatch(int batchSize, int maxAttempts, Duration lease) {
        return claimBatchWithExpired(batchSize, maxAttempts, lease).claimed();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ClaimResult claimBatchWithExpired(int batchSize, int maxAttempts, Duration lease) {
        int expiredLimit = Math.max(batchSize, 100);
        List<OutboxEvent> expiredExhausted = jdbc.query(
            "with expired as ("
                + " select id from notification.outbox_events "
                + " where status = 'PROCESSING' and locked_at < now() - (? * interval '1 millisecond') "
                + " and attempt_count >= ? "
                + " order by locked_at, id limit ? for update skip locked"
                + ") update notification.outbox_events e set status = 'RETRY_EXHAUSTED', locked_at = null, "
                + "payload = case when e.event_type = 'USER_INVITATION_EMAIL_REQUESTED' then '{}'::jsonb else e.payload end, "
                + "last_error = coalesce(e.last_error, 'Processing lease expired after maximum attempts'), updated_at = now() "
                + "from expired x where e.id = x.id "
                + "returning e.id, e.event_type, e.aggregate_type, e.aggregate_id, e.payload::text, e.idempotency_key, e.attempt_count",
            (rs, rowNum) -> mapEvent(rs),
            lease.toMillis(), maxAttempts, expiredLimit);
        List<OutboxEvent> claimed = jdbc.query(
            "with due_pending as ("
                + " select id, available_at, created_at from notification.outbox_events "
                + " where status = 'PENDING' and available_at <= now() and attempt_count < ? "
                + " order by available_at, created_at limit ? for update skip locked"
                + "), stale_processing as ("
                + " select id, available_at, created_at from notification.outbox_events "
                + " where status = 'PROCESSING' and locked_at < now() - (? * interval '1 millisecond') and attempt_count < ? "
                + " order by available_at, created_at limit ? for update skip locked"
                + "), candidates as ("
                + " select id from ("
                + "  select id, available_at, created_at from due_pending "
                + "  union all "
                + "  select id, available_at, created_at from stale_processing"
                + " ) merged order by available_at, created_at limit ?"
                + ") update notification.outbox_events e set status = 'PROCESSING', "
                + "attempt_count = e.attempt_count + 1, locked_at = now(), updated_at = now() "
                + "from candidates c where e.id = c.id "
                + "returning e.id, e.event_type, e.aggregate_type, e.aggregate_id, e.payload::text, e.idempotency_key, e.attempt_count",
            (rs, rowNum) -> mapEvent(rs),
            maxAttempts, batchSize, lease.toMillis(), maxAttempts, batchSize, batchSize);
        return new ClaimResult(claimed, expiredExhausted);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(UUID eventId) {
        jdbc.update("update notification.outbox_events set status = 'PROCESSED', processed_at = now(), "
                + "payload = case when event_type = 'USER_INVITATION_EMAIL_REQUESTED' then '{}'::jsonb else payload end, "
                + "locked_at = null, last_error = null, updated_at = now() where id = ? and status = 'PROCESSING'",
            eventId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markProcessed(UUID eventId, int expectedAttemptCount) {
        int updated = jdbc.update("update notification.outbox_events set status = 'PROCESSED', processed_at = now(), "
                + "payload = case when event_type = 'USER_INVITATION_EMAIL_REQUESTED' then '{}'::jsonb else payload end, "
                + "locked_at = null, last_error = null, updated_at = now() "
                + "where id = ? and status = 'PROCESSING' and attempt_count = ?",
            eventId, expectedAttemptCount);
        return updated == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(OutboxEvent event, int maxAttempts, Duration retryDelay, String error) {
        return markFailed(event, maxAttempts, retryDelay, error, false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(OutboxEvent event, int maxAttempts, Duration retryDelay, String error, boolean nonRetryable) {
        boolean terminal = nonRetryable || event.attemptCount() >= maxAttempts;
        int updated = jdbc.update(
            "update notification.outbox_events set status = ?, available_at = now() + (? * interval '1 millisecond'), "
                + "payload = case when event_type = 'USER_INVITATION_EMAIL_REQUESTED' and ? "
                + "then '{}'::jsonb else payload end, "
                + "locked_at = null, last_error = ?, updated_at = now() "
                + "where id = ? and status = 'PROCESSING' and attempt_count = ?",
            terminal ? "RETRY_EXHAUSTED" : "PENDING",
            retryDelay.toMillis(), terminal, abbreviate(error), event.id(), event.attemptCount());
        return updated == 1 && terminal;
    }

    public long pendingCount() {
        Long count = jdbc.queryForObject("select count(*) from notification.outbox_events where status in ('PENDING', 'PROCESSING')",
            Long.class);
        return count == null ? 0 : count;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeTerminalBefore(Instant cutoff) {
        Boolean locked = jdbc.queryForObject(
            "select pg_try_advisory_xact_lock(hashtext('notification.outbox_events.purge'))",
            Boolean.class);
        if (!Boolean.TRUE.equals(locked)) return 0;
        java.sql.Timestamp cutoffTimestamp = java.sql.Timestamp.from(cutoff);
        int totalDeleted = 0;
        while (true) {
            int deleted = jdbc.update(
                "with doomed as ("
                    + " select id from notification.outbox_events "
                    + " where created_at < ? and status in ('PROCESSED', 'RETRY_EXHAUSTED') limit ?"
                    + ") delete from notification.outbox_events e using doomed d where e.id = d.id",
                cutoffTimestamp, PURGE_BATCH_SIZE);
            totalDeleted += deleted;
            if (deleted < PURGE_BATCH_SIZE) return totalDeleted;
        }
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
