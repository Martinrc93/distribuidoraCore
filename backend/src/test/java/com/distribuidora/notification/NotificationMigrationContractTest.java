package com.distribuidora.notification;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMigrationContractTest {
    @Test
    void persistsIdempotentDeliveryRequestsAndRetryStatus() {
        String migration = readMigration().replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        assertThat(migration)
            .contains("CREATE TABLE NOTIFICATION.DELIVERY_REQUESTS")
            .contains("IDEMPOTENCY_KEY VARCHAR(120) NOT NULL UNIQUE")
            .contains("OUTBOX_EVENT_ID UUID NULL UNIQUE")
            .contains("CHANNEL IN ('EMAIL', 'WHATSAPP')")
            .contains("DOCUMENT_FORMAT IN ('A4', 'TICKET')")
            .contains("STATUS IN ('QUEUED', 'SENDING', 'SENT', 'FAILED', 'RETRY_EXHAUSTED')")
            .doesNotContain("DROP TABLE", "DELETE FROM", "TRUNCATE");
    }

    private String readMigration() {
        try (InputStream stream = getClass().getResourceAsStream("/db/migration/V20__create_notification_delivery_requests.sql")) {
            assertThat(stream).isNotNull();
            if (stream == null) throw new AssertionError("Migration resource is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Could not read migration resource", exception);
        }
    }
}
