package com.distribuidora.notification.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.List;

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

    public OutboxWorker(OutboxRepository repository, ApplicationEventPublisher publisher, MeterRegistry metrics) {
        this.repository = repository;
        this.publisher = publisher;
        this.processed = metrics.counter("app.outbox.events.processed");
        this.failures = metrics.counter("app.outbox.events.failed");
        this.exhausted = metrics.counter("app.outbox.events.exhausted");
        this.batchDuration = metrics.timer("app.outbox.batch.duration");
        Gauge.builder("app.outbox.pending", repository, OutboxRepository::pendingCount).register(metrics);
    }

    public int processBatch(int batchSize) {
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("batchSize debe estar entre 1 y 500");
        Timer.Sample sample = Timer.start();
        List<OutboxEvent> events = repository.claimBatch(batchSize, DEFAULT_MAX_ATTEMPTS, LEASE);
        for (OutboxEvent event : events) {
            try {
                publisher.publishEvent(new OutboxDispatchEvent(event));
                repository.markProcessed(event.id());
                processed.increment();
            } catch (RuntimeException exception) {
                failures.increment();
                Duration retryDelay = backoff(event.attemptCount());
                boolean exhausted = repository.markFailed(event, DEFAULT_MAX_ATTEMPTS, retryDelay,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
                if (exhausted) {
                    this.exhausted.increment();
                    publisher.publishEvent(new OutboxRetryExhaustedEvent(event));
                }
                log.warn("Outbox event {} failed on attempt {}", event.id(), event.attemptCount());
            }
        }
        sample.stop(batchDuration);
        return events.size();
    }

    public static Duration backoff(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 10);
        return Duration.ofSeconds(Math.min(30L * (1L << exponent), 21600L));
    }
}
