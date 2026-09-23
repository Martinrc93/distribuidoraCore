package com.distribuidora.db.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RemainingPriceListsMigrationContractTest {

    @Test
    void migrationSeedsListsFourThroughTenAsInactive() {
        String migration = readMigration();

        assertThat(migration)
                .contains("'00000000-0000-0000-0000-000000000004', 'LISTA_4', 'Lista 4', 'INACTIVE'")
                .contains("'00000000-0000-0000-0000-000000000010', 'LISTA_10', 'Lista 10', 'INACTIVE'")
                .contains("ON CONFLICT (id) DO NOTHING");
    }

    private static String readMigration() {
        try (InputStream stream = RemainingPriceListsMigrationContractTest.class
                .getResourceAsStream("/db/migration/V9__seed_remaining_price_lists.sql")) {
            assertThat(stream).as("migration resource").isNotNull();
            if (stream == null) {
                throw new AssertionError("Migration resource is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Could not read migration resource", exception);
        }
    }
}
