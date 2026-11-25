package com.distribuidora.notification;

import com.distribuidora.notification.application.NonRetryableOutboxException;
import com.distribuidora.notification.application.OutboxDispatchEvent;
import com.distribuidora.notification.application.OutboxEvent;
import com.distribuidora.notification.application.OutboxRepository;
import com.distribuidora.notification.application.OutboxRetryExhaustedEvent;
import com.distribuidora.notification.application.OutboxWorker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxWorkerTest {
    @Test
    void retryBackoffDoublesAndCaps() {
        assertThat(OutboxWorker.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(OutboxWorker.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(OutboxWorker.backoff(4)).isEqualTo(Duration.ofSeconds(240));
        assertThat(OutboxWorker.backoff(20)).isEqualTo(Duration.ofHours(6));
    }

    @Test
    void scalesLeaseByBatchSizeAndJittersRetryBackoff() {
        OutboxWorker worker = new OutboxWorker(mock(OutboxRepository.class), mock(ApplicationEventPublisher.class),
            new SimpleMeterRegistry(), 10_000L);
        assertThat(worker.effectiveLease(1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(worker.effectiveLease(25)).isEqualTo(Duration.ofSeconds(310));
        for (int i = 0; i < 20; i++) {
            Duration jittered = OutboxWorker.backoffWithJitter(2);
            assertThat(jittered).isBetween(Duration.ofSeconds(48), Duration.ofSeconds(72));
        }
    }

    @Test
    void handlesNonRetryableErrorsExpiredFinalAttemptsAndLeaseLoss() {
        OutboxRepository repo = mock(OutboxRepository.class);
        List<Object> published = new ArrayList<>();
        ApplicationEventPublisher publisher = published::add;
        ApplicationEventPublisher failingPublisher = event -> {
            published.add(event);
            if (event instanceof OutboxDispatchEvent) {
                throw new NonRetryableOutboxException("Permanent 400");
            }
        };
        OutboxEvent expired = new OutboxEvent(UUID.randomUUID(), "T", "ORDER", UUID.randomUUID(), "{}", "k-exp", 8);
        OutboxEvent permFail = new OutboxEvent(UUID.randomUUID(), "T", "ORDER", UUID.randomUUID(), "{}", "k-perm", 1);
        SimpleMeterRegistry permRegistry = new SimpleMeterRegistry();
        when(repo.claimBatchWithExpired(10, 8, Duration.ofSeconds(160)))
            .thenReturn(new OutboxRepository.ClaimResult(List.of(permFail), List.of(expired)));
        when(repo.markFailed(eq(permFail), eq(8), eq(Duration.ZERO), any(), eq(true))).thenReturn(true);
        when(repo.pendingCount()).thenReturn(0L);

        OutboxWorker permWorker = new OutboxWorker(repo, failingPublisher, permRegistry, 10_000L);
        assertThat(permWorker.processBatch(10)).isEqualTo(1);
        assertThat(permRegistry.counter("app.outbox.events.exhausted").count()).isEqualTo(2.0);
        assertThat(published.stream().filter(OutboxRetryExhaustedEvent.class::isInstance)).hasSize(2);

        OutboxEvent leaseLost = new OutboxEvent(UUID.randomUUID(), "T", "ORDER", UUID.randomUUID(), "{}", "k-lost", 1);
        SimpleMeterRegistry lostRegistry = new SimpleMeterRegistry();
        when(repo.claimBatchWithExpired(10, 8, Duration.ofSeconds(160)))
            .thenReturn(new OutboxRepository.ClaimResult(List.of(leaseLost), List.of()));
        when(repo.markProcessed(leaseLost.id(), 1)).thenReturn(false);

        OutboxWorker okWorker = new OutboxWorker(repo, publisher, lostRegistry, 10_000L);
        okWorker.processBatch(10);
        verify(repo).markProcessed(leaseLost.id(), 1);
        assertThat(lostRegistry.counter("app.outbox.events.processed").count()).isZero();
    }
}
