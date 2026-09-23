package com.distribuidora.db.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OptionalCustomerTaxIdMigrationContractTest {

    @Test
    void migrationAllowsCustomersWithoutTaxId() {
        try (InputStream stream = OptionalCustomerTaxIdMigrationContractTest.class
                .getResourceAsStream("/db/migration/V10__allow_optional_customer_tax_id.sql")) {
            assertThat(stream).as("migration resource").isNotNull();
            if (stream == null) {
                throw new AssertionError("Migration resource is missing");
            }
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(migration).contains("ALTER COLUMN tax_id DROP NOT NULL");
        } catch (IOException exception) {
            throw new AssertionError("Could not read migration resource", exception);
        }
    }
}
