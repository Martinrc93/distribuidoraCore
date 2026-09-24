package com.distribuidora.notification.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@Profile("!test")
@ConditionalOnProperty(name = "app.notifications.retention.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationRetentionJob {
    private final NotificationRequestStateService stateService;
    private final int retentionDays;
    private final OutboxRepository outboxRepository;
    private final int outboxRetentionDays;

    public NotificationRetentionJob(NotificationRequestStateService stateService,
                                    OutboxRepository outboxRepository,
                                    @Value("${app.notifications.retention.days:90}") int retentionDays,
                                    @Value("${app.outbox.retention.days:90}") int outboxRetentionDays) {
        if (retentionDays < 1) throw new IllegalArgumentException("La retención de notificaciones debe ser al menos un día");
        if (outboxRetentionDays < 1) throw new IllegalArgumentException("La retención de outbox debe ser al menos un día");
        this.stateService = stateService;
        this.retentionDays = retentionDays;
        this.outboxRepository = outboxRepository;
        this.outboxRetentionDays = outboxRetentionDays;
    }

    @Scheduled(cron = "${app.notifications.retention.cron:0 30 3 * * *}")
    public int purge() {
        int requests = stateService.purgeTerminalBefore(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        int events = outboxRepository.purgeTerminalBefore(Instant.now().minus(outboxRetentionDays, ChronoUnit.DAYS));
        return requests + events;
    }
}
