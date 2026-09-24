package com.distribuidora.notification.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@ConditionalOnProperty(name = "app.outbox.worker.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPollingJob {
    private final OutboxWorker worker;

    public OutboxPollingJob(OutboxWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${app.outbox.worker.poll-interval-ms:5000}")
    public void poll() {
        worker.processBatch(50);
    }
}
