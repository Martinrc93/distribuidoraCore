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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class InvitationOutboxPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
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
        repository.claimBatch(100, 8, Duration.ofMinutes(2));
        assertThat(jdbc.queryForObject("select payload::text from notification.outbox_events where id = ?", String.class, abandonedId)).isEqualTo("{}");
        assertThat(jdbc.queryForObject("select status from notification.outbox_events where id = ?", String.class, abandonedId)).isEqualTo("RETRY_EXHAUSTED");
    }

    private UUID enqueue() {
        UUID userId = UUID.randomUUID();
        return transaction.execute(status -> outbox.enqueue(UserInvitationEmailService.EVENT_TYPE, "USER", userId, payload(), "invitation:" + userId));
    }

    private Map<String, String> payload() {
        return Map.of("email", "seller@example.test", "activationLink", "https://app.example.test/activate?token=secret-token", "expiresAt", Instant.now().plusSeconds(1800).toString());
    }
}
