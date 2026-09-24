package com.distribuidora.notification;

import com.distribuidora.notification.application.OutboxWorker;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxWorkerTest {
    @Test
    void retryBackoffDoublesAndCaps() {
        assertThat(OutboxWorker.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(OutboxWorker.backoff(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(OutboxWorker.backoff(4)).isEqualTo(Duration.ofSeconds(240));
        assertThat(OutboxWorker.backoff(20)).isEqualTo(Duration.ofHours(6));
    }
}
