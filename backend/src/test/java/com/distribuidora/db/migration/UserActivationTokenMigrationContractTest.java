package com.distribuidora.db.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class UserActivationTokenMigrationContractTest {

    @Test
    void migrationCreatesUserActivationTokensTable() {
        try (InputStream stream = UserActivationTokenMigrationContractTest.class
                .getResourceAsStream("/db/migration/V13__create_user_activation_tokens_table.sql")) {
            assertThat(stream).as("migration resource V13").isNotNull();
            if (stream == null) {
                throw new AssertionError("Migration resource V13 is missing");
            }
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(migration).contains("CREATE TABLE identity.user_activation_tokens");
            assertThat(migration).contains("user_id UUID NOT NULL REFERENCES identity.users (id)");
            assertThat(migration).contains("token_hash VARCHAR(128) NOT NULL UNIQUE");
            assertThat(migration).contains("expires_at TIMESTAMPTZ NOT NULL");
            assertThat(migration).contains("used_at TIMESTAMPTZ");
            assertThat(migration).contains("CREATE INDEX ix_user_activation_tokens_user_id");
        } catch (IOException exception) {
            throw new AssertionError("Could not read migration resource", exception);
        }
    }
}
