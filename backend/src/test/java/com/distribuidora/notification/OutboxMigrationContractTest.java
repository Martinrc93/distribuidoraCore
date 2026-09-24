package com.distribuidora.notification;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxMigrationContractTest {
    @Test
    void createsIdempotentClaimableOutboxAndRetryStates() {
        String migration = readMigration().replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
        assertThat(migration)
            .contains("CREATE TABLE NOTIFICATION.OUTBOX_EVENTS")
            .contains("IDEMPOTENCY_KEY VARCHAR(200) NOT NULL UNIQUE")
            .contains("PAYLOAD JSONB NOT NULL")
            .contains("STATUS IN ('PENDING', 'PROCESSING', 'PROCESSED', 'RETRY_EXHAUSTED')")
            .contains("IX_OUTBOX_PENDING_AVAILABLE")
            .contains("IX_OUTBOX_PROCESSING_LEASE")
            .doesNotContain("DROP TABLE", "DELETE FROM", "TRUNCATE");
    }

    private String readMigration() {
        try (InputStream stream = getClass().getResourceAsStream("/db/migration/V19__create_transactional_outbox.sql")) {
            assertThat(stream).isNotNull();
            if (stream == null) throw new AssertionError("Migration resource is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Could not read migration resource", exception);
        }
    }
}
