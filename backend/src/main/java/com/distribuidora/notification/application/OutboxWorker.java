package com.distribuidora.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class OutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final int DEFAULT_MAX_ATTEMPTS = 8;
    private static final Duration LEASE = Duration.ofMinutes(2);
    private final OutboxRepository repository;
    private final ApplicationEventPublisher publisher;
    private final Counter processed;
    private final Counter failures;
    private final Counter exhausted;
    private final Timer batchDuration;
    private final long providerTimeoutMs;
    private final AtomicLong pendingCount = new AtomicLong(0);

    public OutboxWorker(OutboxRepository repository, ApplicationEventPublisher publisher, MeterRegistry metrics) {
        this(repository, publisher, metrics, 10_000L);
    }

    @Autowired
    public OutboxWorker(OutboxRepository repository, ApplicationEventPublisher publisher, MeterRegistry metrics,
                        @Value("${app.notifications.timeout-ms:10000}") long providerTimeoutMs) {
        this.repository = repository;
        this.publisher = publisher;
        this.processed = metrics.counter("app.outbox.events.processed");
        this.failures = metrics.counter("app.outbox.events.failed");
        this.exhausted = metrics.counter("app.outbox.events.exhausted");
        this.batchDuration = metrics.timer("app.outbox.batch.duration");
        this.providerTimeoutMs = Math.max(500L, Math.min(providerTimeoutMs, 60_000L));
        Gauge.builder("app.outbox.pending", pendingCount, AtomicLong::get).register(metrics);
    }

    public int processBatch(int batchSize) {
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("batchSize debe estar entre 1 y 500");
        Timer.Sample sample = Timer.start();
        Duration lease = effectiveLease(batchSize);
        OutboxRepository.ClaimResult batch = repository.claimBatchWithExpired(batchSize, DEFAULT_MAX_ATTEMPTS, lease);
        for (OutboxEvent expiredEvent : batch.expiredExhausted()) {
            this.exhausted.increment();
            publisher.publishEvent(new OutboxRetryExhaustedEvent(expiredEvent));
            log.warn("Outbox event {} expired after {} attempts and was marked RETRY_EXHAUSTED",
                expiredEvent.id(), expiredEvent.attemptCount());
        }
        for (OutboxEvent event : batch.claimed()) {
            try {
                publisher.publishEvent(new OutboxDispatchEvent(event));
                if (repository.markProcessed(event.id(), event.attemptCount())) {
                    processed.increment();
                } else {
                    log.warn("Outbox event {} lost processing lease before completion (attempt {})",
                        event.id(), event.attemptCount());
                }
            } catch (NonRetryableOutboxException exception) {
                failures.increment();
                boolean exhaustedNow = repository.markFailed(event, DEFAULT_MAX_ATTEMPTS, Duration.ZERO,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage(), true);
                if (exhaustedNow) {
                    this.exhausted.increment();
                    publisher.publishEvent(new OutboxRetryExhaustedEvent(event));
                }
                log.warn("Outbox event {} failed permanently on attempt {}", event.id(), event.attemptCount());
            } catch (RuntimeException exception) {
                failures.increment();
                Duration retryDelay = backoffWithJitter(event.attemptCount());
                boolean exhaustedNow = repository.markFailed(event, DEFAULT_MAX_ATTEMPTS, retryDelay,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage(), false);
                if (exhaustedNow) {
                    this.exhausted.increment();
                    publisher.publishEvent(new OutboxRetryExhaustedEvent(event));
                }
                log.warn("Outbox event {} failed on attempt {}", event.id(), event.attemptCount());
            }
        }
        try {
            pendingCount.set(repository.pendingCount());
        } catch (RuntimeException ignored) {
        }
        sample.stop(batchDuration);
        return batch.claimed().size();
    }

    public Duration effectiveLease(int batchSize) {
        long batchWindowMs = (long) batchSize * providerTimeoutMs + 60_000L;
        return Duration.ofMillis(Math.max(LEASE.toMillis(), batchWindowMs));
    }

    public static Duration backoffWithJitter(int attempt) {
        long baseMillis = backoff(attempt).toMillis();
        double factor = ThreadLocalRandom.current().nextDouble(0.8d, 1.2d);
        return Duration.ofMillis(Math.max(1000L, Math.round(baseMillis * factor)));
    }

    public static Duration backoff(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 10);
        return Duration.ofSeconds(Math.min(30L * (1L << exponent), 21600L));
    }
}
