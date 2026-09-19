package com.distribuidora.db.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class PricingMigrationContractTest {

    private static final String MIGRATION_RESOURCE =
            "/db/migration/V5__create_pricing_tables.sql";

    @Test
    void migrationDefinesPricingSchemaAndSeedContract() {
        String migration = readMigration();

        assertThat(migration)
                .contains("CREATE TABLE catalog.price_lists")
                .contains("id UUID PRIMARY KEY")
                .contains("code VARCHAR(40) NOT NULL UNIQUE")
                .contains("name VARCHAR(120) NOT NULL")
                .contains("status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'")
                .contains("is_default BOOLEAN NOT NULL DEFAULT FALSE")
                .contains("CREATE TABLE catalog.product_prices")
                .contains("price_list_id UUID NOT NULL REFERENCES catalog.price_lists (id)")
                .contains("product_id UUID NOT NULL REFERENCES catalog.products (id)")
                .contains("price NUMERIC(19,4) NOT NULL")
                .contains("CONSTRAINT ck_product_price_amount CHECK (price >= 0)")
                .contains("ADD COLUMN price_list_id UUID NULL")
                .contains("FOREIGN KEY (price_list_id) REFERENCES catalog.price_lists (id)");

        String normalized = normalize(migration);
        assertThat(normalized)
                .contains("INSERT INTO CATALOG.PRICE_LISTS (ID, CODE, NAME, STATUS, IS_DEFAULT, CREATED_AT, UPDATED_AT) "
                        + "VALUES "
                        + "('00000000-0000-0000-0000-000000000001', 'GENERAL', 'LISTA GENERAL', 'ACTIVE', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), "
                        + "('00000000-0000-0000-0000-000000000002', 'LISTA_2', 'LISTA 2', 'ACTIVE', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP), "
                        + "('00000000-0000-0000-0000-000000000003', 'LISTA_3', 'LISTA 3', 'ACTIVE', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);")
                .contains("FROM CATALOG.PRICE_LISTS CROSS JOIN CATALOG.PRODUCTS WHERE PRICE_LISTS.CODE IN ('GENERAL', 'LISTA_2', 'LISTA_3')");

        assertThat(countOccurrences(normalized, "'GENERAL'"))
                .as("GENERAL must be seeded once as the only default price list and once in the migration filter")
                .isEqualTo(2);
        assertThat(countOccurrences(normalized, "'LISTA_2'"))
                .isEqualTo(2);
        assertThat(countOccurrences(normalized, "'LISTA_3'"))
                .isEqualTo(2);
    }

    private static String readMigration() {
        try (InputStream stream = PricingMigrationContractTest.class.getResourceAsStream(MIGRATION_RESOURCE)) {
            assertThat(stream).as("migration resource").isNotNull();
            if (stream == null) {
                throw new AssertionError("Migration resource is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new AssertionError("Could not read migration resource", exception);
        }
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    private static int countOccurrences(String value, String token) {
        return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }
}
