package com.distribuidora.db.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenMigrationContractTest {

    @Test
    void migrationAddsReplacedByToRefreshTokens() {
        try (InputStream stream = RefreshTokenMigrationContractTest.class
                .getResourceAsStream("/db/migration/V12__add_refresh_token_rotation_support.sql")) {
            assertThat(stream).as("migration resource V12").isNotNull();
            if (stream == null) {
                throw new AssertionError("Migration resource V12 is missing");
            }
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(migration).contains("ALTER TABLE identity.refresh_tokens");
            assertThat(migration).contains("ADD COLUMN replaced_by UUID REFERENCES identity.refresh_tokens (id)");
        } catch (IOException exception) {
            throw new AssertionError("Could not read migration resource", exception);
        }
    }
}
