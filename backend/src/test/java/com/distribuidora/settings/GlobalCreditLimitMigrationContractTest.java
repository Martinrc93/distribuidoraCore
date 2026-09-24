package com.distribuidora.settings;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalCreditLimitMigrationContractTest {
    @Test
    void createsNullableGlobalLimitAndOrderSnapshotsWithoutBlockingOrders() {
        String migration = readMigration().replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);

        assertThat(migration)
            .contains("CREATE TABLE APP.BUSINESS_SETTINGS")
            .contains("CREDIT_LIMIT NUMERIC(19,4) NULL")
            .contains("INSERT INTO APP.BUSINESS_SETTINGS (ID, CREDIT_LIMIT) VALUES (1, NULL)")
            .contains("ADD COLUMN CREDIT_LIMIT_EXCEEDED BOOLEAN NOT NULL DEFAULT FALSE")
            .contains("ADD COLUMN CREDIT_LIMIT_SNAPSHOT NUMERIC(19,4) NULL")
            .contains("ADD COLUMN PROJECTED_BALANCE_SNAPSHOT NUMERIC(19,4) NULL")
            .doesNotContain("DROP TABLE", "DELETE FROM", "TRUNCATE");
    }

    private String readMigration() {
        try (InputStream stream = getClass().getResourceAsStream("/db/migration/V18__add_global_credit_limit.sql")) {
            assertThat(stream).isNotNull();
            if (stream == null) throw new AssertionError("Migration resource is missing");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Could not read migration resource", exception);
        }
    }
}
