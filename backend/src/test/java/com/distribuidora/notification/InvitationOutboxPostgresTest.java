package com.distribuidora.notification;

import com.distribuidora.identity.application.UserInvitationEmailService;
import com.distribuidora.notification.application.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import com.distribuidora.testinfra.SharedPostgres;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvitationOutboxPostgresTest {
    private static final SharedPostgres.Database POSTGRES = SharedPostgres.databaseFor(InvitationOutboxPostgresTest.class);
    static JdbcTemplate jdbc;
    static TransactionTemplate transaction;
    static OutboxService outbox;
    static OutboxRepository repository;

    @BeforeAll
    static void initialize() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        outbox = new OutboxService(jdbc, new ObjectMapper());
        repository = new OutboxRepository(jdbc);
    }

    @Test
    void queueRollsBackAndRetriesPreserveLinkUntilSuccessfulSend() {
        String key = "rolled-back:" + UUID.randomUUID();
        transaction.executeWithoutResult(status -> {
            outbox.enqueue(UserInvitationEmailService.EVENT_TYPE, "USER", UUID.randomUUID(), payload(), key);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from notification.outbox_events where idempotency_key = ?", Long.class, key)).isZero();

        UUID eventId = enqueue();
        OutboxEvent first = repository.claimBatch(100, 8, Duration.ofMinutes(2)).stream().filter(e -> e.id().equals(eventId)).findFirst().orElseThrow();
        assertThat(repository.markFailed(first, 8, Duration.ZERO, "Provider unavailable")).isFalse();
        OutboxEvent retry = repository.claimBatch(100, 8, Duration.ofMinutes(2)).stream().filter(e -> e.id().equals(eventId)).findFirst().orElseThrow();
        assertThat(retry.id()).isEqualTo(first.id());
        assertThat(retry.payload()).contains("secret-token");
        repository.markProcessed(eventId);
        var saved = jdbc.queryForMap("select status, payload::text from notification.outbox_events where id = ?", eventId);
        assertThat(saved.get("status")).isEqualTo("PROCESSED");
        assertThat(saved.get("payload")).isEqualTo("{}");
    }

    @Test
    void exhaustedAndAbandonedFinalAttemptsRemoveActivationSecrets() {
        UUID failedId = enqueue();
        jdbc.update("update notification.outbox_events set status = 'PROCESSING', attempt_count = 8 where id = ?", failedId);
        assertThat(repository.markFailed(new OutboxEvent(failedId, UserInvitationEmailService.EVENT_TYPE, "USER", UUID.randomUUID(), "{}", "failed", 8),
            8, Duration.ZERO, "Provider unavailable")).isTrue();
        assertThat(jdbc.queryForObject("select payload::text from notification.outbox_events where id = ?", String.class, failedId)).isEqualTo("{}");

        UUID abandonedId = enqueue();
        jdbc.update("update notification.outbox_events set status = 'PROCESSING', attempt_count = 8, locked_at = now() - interval '3 minutes' where id = ?", abandonedId);
        OutboxRepository.ClaimResult claimed = repository.claimBatchWithExpired(100, 8, Duration.ofMinutes(2));
        assertThat(claimed.expiredExhausted()).extracting(OutboxEvent::id).contains(abandonedId);
        assertThat(jdbc.queryForObject("select payload::text from notification.outbox_events where id = ?", String.class, abandonedId)).isEqualTo("{}");
        assertThat(jdbc.queryForObject("select status from notification.outbox_events where id = ?", String.class, abandonedId)).isEqualTo("RETRY_EXHAUSTED");
    }

    @Test
    void fencingTokenPreventsStaleAttemptCompletionAndRetentionPreservesFailedRequests() {
        UUID eventId = enqueue();
        OutboxEvent claimed = repository.claimBatchWithExpired(100, 8, Duration.ofMinutes(2)).claimed()
            .stream().filter(e -> e.id().equals(eventId)).findFirst().orElseThrow();
        assertThat(repository.markProcessed(eventId, claimed.attemptCount() + 1)).isFalse();
        assertThat(jdbc.queryForObject("select status from notification.outbox_events where id = ?", String.class, eventId)).isEqualTo("PROCESSING");
        assertThat(repository.markProcessed(eventId, claimed.attemptCount())).isTrue();
        assertThat(jdbc.queryForObject("select status from notification.outbox_events where id = ?", String.class, eventId)).isEqualTo("PROCESSED");

        UUID customerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID saleId = UUID.randomUUID();
        jdbc.update("insert into customer.customers(id, business_name, created_at) values (?, 'Retention Customer', now())", customerId);
        jdbc.update("insert into orders.orders(id, order_number, customer_id, status, subtotal, discount, total, created_at) values (?, ?, ?, 'CONFIRMED', 10, 0, 10, now())",
            orderId, "RET-" + orderId.toString().substring(0, 12), customerId);
        jdbc.update("insert into sale.sales(id, sale_number, order_id, customer_id, account_customer_id, status, total, paid, created_at) values (?, ?, ?, ?, ?, 'CONFIRMED', 10, 0, now())",
            saleId, "RET-" + orderId.toString().substring(0, 12), orderId, customerId, customerId);

        UUID oldSent = UUID.randomUUID();
        UUID oldExhausted = UUID.randomUUID();
        UUID oldFailed = UUID.randomUUID();
        for (Object[] row : new Object[][]{
            {oldSent, "SENT"}, {oldExhausted, "RETRY_EXHAUSTED"}, {oldFailed, "FAILED"}
        }) {
            jdbc.update("""
                insert into notification.delivery_requests(id, order_id, sale_id, channel, document_format, recipient, idempotency_key, status, created_at, updated_at)
                values (?, ?, ?, 'EMAIL', 'TICKET', 'ret@example.test', ?, ?, now() - interval '120 days', now() - interval '120 days')
                """, row[0], orderId, saleId, "ret-key-" + row[0], row[1]);
        }

        var stateService = new NotificationRequestStateService(jdbc, org.mockito.Mockito.mock(com.distribuidora.audit.application.AuditService.class));
        int purged = transaction.execute(status -> stateService.purgeTerminalBefore(Instant.now().minus(Duration.ofDays(90))));
        assertThat(purged).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notification.delivery_requests where id = ?", Integer.class, oldSent)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification.delivery_requests where id = ?", Integer.class, oldExhausted)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification.delivery_requests where id = ?", Integer.class, oldFailed)).isEqualTo(1);
    }

    private UUID enqueue() {
        UUID userId = UUID.randomUUID();
        return transaction.execute(status -> outbox.enqueue(UserInvitationEmailService.EVENT_TYPE, "USER", userId, payload(), "invitation:" + userId));
    }

    private Map<String, String> payload() {
        return Map.of("email", "seller@example.test", "activationLink", "https://app.example.test/activate?token=secret-token", "expiresAt", Instant.now().plusSeconds(1800).toString());
    }
}
