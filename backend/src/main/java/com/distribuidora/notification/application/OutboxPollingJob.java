package com.distribuidora.notification.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@ConditionalOnProperty(name = "app.outbox.worker.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPollingJob {
    private final OutboxWorker worker;
    private final int batchSize;

    public OutboxPollingJob(OutboxWorker worker,
                            @Value("${app.outbox.worker.batch-size:10}") int batchSize) {
        this.worker = worker;
        this.batchSize = Math.max(1, Math.min(batchSize, 500));
    }

    @Scheduled(fixedDelayString = "${app.outbox.worker.poll-interval-ms:5000}")
    public void poll() {
        worker.processBatch(batchSize);
    }
}
